package com.neracalab.backend.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document into overlapping chunks for embedding. The text is cleaned (runs of spaces and blank lines
 * collapsed, as PDF tables are full of them), cut at line ends where possible, else at word boundaries, into chunks
 * of at most {@code chunkChars} characters; each chunk starts with the last {@code overlap} characters of the
 * previous one (from a word boundary), so a sentence cut at a boundary is still found whole in one chunk. Chunks
 * keep the pages they come from.
 */
public final class TextChunker {

    /** A piece of the document: its text and its page (1-based; {@code null} for a text without pages). */
    public record Part(String text, Integer page) {
    }

    public record Chunk(int index, String text, Integer pageFrom, Integer pageTo) {
    }

    private record Unit(String text, Integer page) {
    }

    private final int chunkChars;
    private final int overlap;

    public TextChunker(int chunkChars, int overlap) {
        if (chunkChars < 200 || overlap < 0 || overlap >= chunkChars / 2) {
            throw new IllegalArgumentException("chunk size " + chunkChars + " / overlap " + overlap + " not usable");
        }
        this.chunkChars = chunkChars;
        this.overlap = overlap;
    }

    /** "a   b \t c" -> "a b c"; blank and whitespace-only lines dropped. */
    static List<String> cleanLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.replace((char) 0xA0, ' ').split("\\R")) {
            String l = line.replaceAll("[\\s\\p{Z}]+", " ").trim();
            if (!l.isEmpty()) {
                lines.add(l);
            }
        }
        return lines;
    }

    public List<Chunk> chunk(List<Part> parts) {
        List<Unit> units = new ArrayList<>();
        for (Part part : parts) {
            for (String line : cleanLines(part.text() == null ? "" : part.text())) {
                // a line longer than a chunk is cut at word boundaries
                for (String piece : split(line, chunkChars - overlap - 1)) {
                    units.add(new Unit(piece, part.page()));
                }
            }
        }
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Integer from = null;
        Integer to = null;
        boolean hasNew = false;           // the chunk holds more than the overlap of the previous one
        for (Unit unit : units) {
            int added = (text.isEmpty() ? 0 : 1) + unit.text().length();
            if (hasNew && text.length() + added > chunkChars) {
                chunks.add(new Chunk(chunks.size(), text.toString(), from, to));
                String tail = tail(text.toString());
                text.setLength(0);
                text.append(tail);
                from = tail.isEmpty() ? null : to;
                hasNew = false;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(unit.text());
            if (from == null) {
                from = unit.page();
            }
            to = unit.page();
            hasNew = true;
        }
        if (hasNew) {
            chunks.add(new Chunk(chunks.size(), text.toString(), from, to));
        }
        return chunks;
    }

    /** The last {@code overlap} characters, starting at a word boundary. */
    private String tail(String text) {
        if (overlap == 0 || text.length() <= overlap) {
            return overlap == 0 ? "" : text;
        }
        String t = text.substring(text.length() - overlap);
        int space = t.indexOf(' ');
        int newline = t.indexOf('\n');
        int cut = space < 0 ? newline : newline < 0 ? space : Math.min(space, newline);
        return cut < 0 ? t : t.substring(cut + 1);
    }

    /** Pieces of at most {@code max} characters, cut at the last space before the limit where there is one. */
    static List<String> split(String line, int max) {
        List<String> pieces = new ArrayList<>();
        String rest = line;
        while (rest.length() > max) {
            int cut = rest.lastIndexOf(' ', max);
            if (cut <= max / 2) {
                cut = max;
            }
            pieces.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) {
            pieces.add(rest);
        }
        return pieces;
    }
}
