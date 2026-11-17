package com.neracalab.backend.macro.source;

import com.neracalab.backend.crawl.SourceException;

/**
 * TIFF LZW decompression (TIFF 6.0, section 13): codes most significant bit first, 9 to 12 bits wide, the code width
 * grows one code early (at 511, 1023 and 2047 table entries), 256 clears the table and 257 ends the strip or tile.
 */
final class TiffLzw {

    private static final int CLEAR = 256;
    private static final int END = 257;
    private static final int MAX_TABLE = 4096;

    private TiffLzw() {
    }

    /**
     * @param expected the size the decoded strip or tile must have (its width x height x bytes per sample)
     * @throws SourceException a corrupt stream, or one that decodes to another size
     */
    static byte[] decode(byte[] data, int expected) {
        byte[] out = new byte[expected];
        int outPos = 0;
        // the table: entry i is the prefix entry (or -1) plus its last byte; its first byte and length are kept to
        // write an entry backwards without building strings
        int[] prefix = new int[MAX_TABLE];
        byte[] suffix = new byte[MAX_TABLE];
        byte[] first = new byte[MAX_TABLE];
        int[] length = new int[MAX_TABLE];
        for (int i = 0; i < 256; i++) {
            prefix[i] = -1;
            suffix[i] = (byte) i;
            first[i] = (byte) i;
            length[i] = 1;
        }
        int next = 258;
        int width = 9;
        int previous = -1;
        long bits = 0;
        int bitCount = 0;
        int inPos = 0;
        while (true) {
            while (bitCount < width) {
                if (inPos >= data.length) {
                    return finish(out, outPos, expected);
                }
                bits = (bits << 8) | (data[inPos++] & 0xFF);
                bitCount += 8;
            }
            int code = (int) ((bits >>> (bitCount - width)) & ((1 << width) - 1));
            bitCount -= width;
            if (code == CLEAR) {
                next = 258;
                width = 9;
                previous = -1;
                continue;
            }
            if (code == END) {
                return finish(out, outPos, expected);
            }
            int entry;
            if (previous < 0) {
                if (code > 255) {
                    throw new SourceException("LZW: code " + code + " after a clear");
                }
                entry = code;
            } else if (code < next) {
                entry = code;
                add(prefix, suffix, first, length, next++, previous, first[code]);
            } else if (code == next) {
                add(prefix, suffix, first, length, next++, previous, first[previous]);
                entry = code;
            } else {
                throw new SourceException("LZW: code " + code + " is beyond the table (" + next + ")");
            }
            int len = length[entry];
            if (outPos + len > expected) {
                throw new SourceException("LZW: the data decode to more than " + expected + " bytes");
            }
            for (int k = outPos + len - 1, e = entry; k >= outPos; k--, e = prefix[e]) {
                out[k] = suffix[e];
            }
            outPos += len;
            previous = entry;
            if (next >= MAX_TABLE) {
                // a full table must be followed by a clear code; leave width at 12
                next = MAX_TABLE;
            }
            if (next == 511 || next == 1023 || next == 2047) {
                width++;
            }
        }
    }

    private static void add(int[] prefix, byte[] suffix, byte[] first, int[] length, int at, int previous, byte last) {
        if (at >= MAX_TABLE) {
            throw new SourceException("LZW: the table is full without a clear code");
        }
        prefix[at] = previous;
        suffix[at] = last;
        first[at] = first[previous];
        length[at] = length[previous] + 1;
    }

    private static byte[] finish(byte[] out, int outPos, int expected) {
        if (outPos != expected) {
            throw new SourceException("LZW: the data decode to " + outPos + " bytes, expected " + expected);
        }
        return out;
    }
}
