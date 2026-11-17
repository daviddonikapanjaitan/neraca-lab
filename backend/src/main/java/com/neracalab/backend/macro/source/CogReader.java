package com.neracalab.backend.macro.source;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;

/**
 * Reads pixels of a cloud-optimized GeoTIFF over HTTP range requests, a few tiles at a time: the header with the image
 * directories (one request), then only the tiles asked for, adjacent tiles in one request. Only what CHIRPS uses is
 * supported - a classic little-endian TIFF, one band of 32-bit floats, tiled, LZW or no compression, no predictor, a
 * geographic grid given by ModelPixelScale and ModelTiepoint - and anything else fails loudly.
 * <p>
 * The full-resolution image (the first directory) is read: the overviews of the CHIRPS files are resampled across the
 * sea mask and give wrong values on the coasts (Java in September 2015: 12.7 mm at full resolution, 121 mm in the
 * 0.2-degree overview).
 */
final class CogReader {

    /** The first bytes read: the CHIRPS files keep all directories and tile offsets in their first ~2.5 KB. */
    static final int HEADER_BYTES = 16_384;

    /** Fetches {@code first .. last} (inclusive) of the file. */
    @FunctionalInterface
    interface Ranges {

        byte[] get(long first, long last);
    }

    final int width;
    final int height;
    final int tileWidth;
    final int tileHeight;
    final int tilesAcross;
    /** Longitude / latitude of the image's top-left corner and the size of a pixel, degrees. */
    final double x0;
    final double y0;
    final double pixelWidth;
    final double pixelHeight;
    private final int compression;
    private final long[] offsets;
    private final long[] byteCounts;
    private final Ranges ranges;
    private final byte[] header;
    private final Map<Integer, float[]> tiles = new HashMap<>();

    private CogReader(byte[] header, Ranges ranges) {
        this.header = header;
        this.ranges = ranges;
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (header.length < 8 || header[0] != 'I' || header[1] != 'I' || b.getShort(2) != 42) {
            throw new SourceException("GeoTIFF: not a classic little-endian TIFF");
        }
        long ifd = Integer.toUnsignedLong(b.getInt(4));
        Map<Integer, long[]> tags = new HashMap<>();
        Map<Integer, double[]> doubles = new HashMap<>();
        int entries = Short.toUnsignedInt(bytes(ifd, 2).getShort(0));
        ByteBuffer dir = bytes(ifd + 2, entries * 12L);
        for (int i = 0; i < entries; i++) {
            int tag = Short.toUnsignedInt(dir.getShort(i * 12));
            int type = Short.toUnsignedInt(dir.getShort(i * 12 + 2));
            long count = Integer.toUnsignedLong(dir.getInt(i * 12 + 4));
            int size = switch (type) {
                case 3 -> 2;        // SHORT
                case 4 -> 4;        // LONG
                case 12 -> 8;       // DOUBLE
                default -> 0;       // not needed
            };
            if (size == 0 || count == 0) {
                continue;
            }
            ByteBuffer values = count * size <= 4 ? dir.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(i * 12 + 8).slice()
                    .order(ByteOrder.LITTLE_ENDIAN)
                    : bytes(Integer.toUnsignedLong(dir.getInt(i * 12 + 8)), count * size);
            if (type == 12) {
                double[] d = new double[(int) count];
                for (int k = 0; k < count; k++) {
                    d[k] = values.getDouble(k * 8);
                }
                doubles.put(tag, d);
            } else {
                long[] l = new long[(int) count];
                for (int k = 0; k < count; k++) {
                    l[k] = type == 3 ? Short.toUnsignedInt(values.getShort(k * 2)) : Integer.toUnsignedLong(values.getInt(k * 4));
                }
                tags.put(tag, l);
            }
        }
        width = (int) one(tags, 256, "ImageWidth");
        height = (int) one(tags, 257, "ImageLength");
        long bits = one(tags, 258, "BitsPerSample");
        compression = (int) one(tags, 259, "Compression");
        long samples = tags.containsKey(277) ? tags.get(277)[0] : 1;
        long predictor = tags.containsKey(317) ? tags.get(317)[0] : 1;
        long format = tags.containsKey(339) ? tags.get(339)[0] : 1;
        if (bits != 32 || samples != 1 || format != 3) {
            throw new SourceException("GeoTIFF: expected one band of 32-bit floats, got " + samples + " x " + bits
                    + " bits, sample format " + format);
        }
        if (compression != 1 && compression != 5) {
            throw new SourceException("GeoTIFF: compression " + compression + " is not supported (LZW or none)");
        }
        if (predictor != 1) {
            throw new SourceException("GeoTIFF: predictor " + predictor + " is not supported");
        }
        if (!tags.containsKey(322) || !tags.containsKey(323) || !tags.containsKey(324) || !tags.containsKey(325)) {
            throw new SourceException("GeoTIFF: not tiled");
        }
        tileWidth = (int) tags.get(322)[0];
        tileHeight = (int) tags.get(323)[0];
        offsets = tags.get(324);
        byteCounts = tags.get(325);
        tilesAcross = (width + tileWidth - 1) / tileWidth;
        int tilesDown = (height + tileHeight - 1) / tileHeight;
        if (offsets.length != (long) tilesAcross * tilesDown || byteCounts.length != offsets.length) {
            throw new SourceException("GeoTIFF: " + offsets.length + " tile offsets for " + tilesAcross + " x " + tilesDown
                    + " tiles");
        }
        double[] scale = doubles.get(33550);
        double[] tie = doubles.get(33922);
        if (scale == null || tie == null || scale.length < 2 || tie.length < 6) {
            throw new SourceException("GeoTIFF: no ModelPixelScale / ModelTiepoint");
        }
        pixelWidth = scale[0];
        pixelHeight = scale[1];
        x0 = tie[3] - tie[0] * pixelWidth;
        y0 = tie[4] + tie[1] * pixelHeight;
    }

    /** Reads the header of a remote file; 404: {@code null} (the file is not published yet). */
    static CogReader open(PoliteHttpClient http, URI uri) {
        PoliteHttpClient.Response first = http.getRange(uri, 0, HEADER_BYTES - 1);
        if (first.status() == 404) {
            return null;
        }
        PoliteHttpClient.requireOk(uri, first);
        if (first.status() != 206) {
            throw new SourceException(PoliteHttpClient.describe(uri) + " ignored the byte range (HTTP " + first.status() + ")");
        }
        return new CogReader(first.body(), (a, b) -> PoliteHttpClient.requireRange(uri, http.getRange(uri, a, b), b - a + 1));
    }

    /** A reader over bytes already in memory (tests). */
    static CogReader of(byte[] file) {
        return new CogReader(java.util.Arrays.copyOf(file, Math.min(file.length, HEADER_BYTES)),
                (a, b) -> java.util.Arrays.copyOfRange(file, (int) a, (int) b + 1));
    }

    private ByteBuffer bytes(long offset, long length) {
        if (offset + length <= header.length) {
            return ByteBuffer.wrap(header, (int) offset, (int) length).slice().order(ByteOrder.LITTLE_ENDIAN);
        }
        return ByteBuffer.wrap(ranges.get(offset, offset + length - 1)).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static long one(Map<Integer, long[]> tags, int tag, String name) {
        long[] v = tags.get(tag);
        if (v == null || v.length == 0) {
            throw new SourceException("GeoTIFF: no " + name);
        }
        return v[0];
    }

    /** The column of a longitude ({@code -1} or {@code width} beyond the image). */
    int column(double lon) {
        return (int) Math.floor((lon - x0) / pixelWidth);
    }

    /** The row of a latitude. */
    int row(double lat) {
        return (int) Math.floor((y0 - lat) / pixelHeight);
    }

    double lon(int column) {
        return x0 + (column + 0.5) * pixelWidth;
    }

    double lat(int row) {
        return y0 - (row + 0.5) * pixelHeight;
    }

    /** The tile index of a pixel. */
    int tileOf(int row, int column) {
        return (row / tileHeight) * tilesAcross + column / tileWidth;
    }

    /** Reads the given tiles: adjacent tiles of the file in one request each run. */
    void load(SortedSet<Integer> wanted) {
        TreeSet<Integer> missing = new TreeSet<>(java.util.Comparator.<Integer>comparingLong(t -> offsets[t])
                .thenComparingInt(t -> t));
        for (int t : wanted) {
            if (t < 0 || t >= offsets.length) {
                throw new SourceException("GeoTIFF: no tile " + t);
            }
            if (tiles.containsKey(t)) {
                continue;
            }
            if (byteCounts[t] == 0) {
                // a sparse tile: every pixel is NoData
                float[] empty = new float[tileWidth * tileHeight];
                java.util.Arrays.fill(empty, Float.NaN);
                tiles.put(t, empty);
            } else {
                missing.add(t);
            }
        }
        List<Integer> run = new ArrayList<>();
        for (int t : missing) {
            if (!run.isEmpty()) {
                int last = run.getLast();
                if (offsets[last] + byteCounts[last] != offsets[t]) {
                    read(run);
                    run.clear();
                }
            }
            run.add(t);
        }
        if (!run.isEmpty()) {
            read(run);
        }
    }

    private void read(List<Integer> run) {
        long start = offsets[run.getFirst()];
        int last = run.getLast();
        byte[] data = ranges.get(start, offsets[last] + byteCounts[last] - 1);
        int expected = tileWidth * tileHeight * 4;
        for (int t : run) {
            int from = (int) (offsets[t] - start);
            byte[] raw = java.util.Arrays.copyOfRange(data, from, from + (int) byteCounts[t]);
            byte[] decoded = compression == 5 ? TiffLzw.decode(raw, expected) : raw;
            if (decoded.length != expected) {
                throw new SourceException("GeoTIFF: tile " + t + " has " + decoded.length + " bytes, expected " + expected);
            }
            float[] values = new float[tileWidth * tileHeight];
            ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
            tiles.put(t, values);
        }
    }

    /** The value of a pixel of a loaded tile. */
    float value(int row, int column) {
        float[] tile = tiles.get(tileOf(row, column));
        if (tile == null) {
            throw new IllegalStateException("Tile of pixel " + row + "," + column + " is not loaded");
        }
        return tile[(row % tileHeight) * tileWidth + column % tileWidth];
    }
}
