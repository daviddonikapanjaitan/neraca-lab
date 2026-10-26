package com.neracalab.backend.registration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import com.neracalab.backend.rag.PdfText;

/**
 * The text of a PDF with the position of every piece of it, page by page (Apache PDFBox), so a table can be read by
 * its columns. Coordinates are those of the page as displayed (a rotated page is turned upright): x from the left, y
 * from the top (the baseline), in points.
 */
public final class PdfWords {

    /**
     * A piece of text and where it is: {@code x0} .. {@code x1} horizontally, {@code y} the baseline.
     */
    public record Word(double x0, double x1, double y, String text) {

        public double center() {
            return (x0 + x1) / 2;
        }
    }

    /**
     * One page: its number (1-based), its text in reading order, the runs of text PDFBox separates by a gap (a table
     * cell's line, e.g. "Jumlah Saham" or "Jl. Kopo Sayati") and the same text split at every blank into words.
     */
    public record Page(int number, String text, List<Word> runs, List<Word> words) {
    }

    private PdfWords() {
    }

    /**
     * @throws PdfText.PdfException not a PDF or password protected
     */
    public static List<Page> read(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            List<Page> pages = new ArrayList<>();
            for (int p = 1; p <= document.getNumberOfPages(); p++) {
                Collector collector = new Collector();
                collector.setSortByPosition(true);
                collector.setStartPage(p);
                collector.setEndPage(p);
                String text = collector.getText(document);
                pages.add(new Page(p, text, List.copyOf(collector.runs), List.copyOf(collector.words)));
            }
            return pages;
        } catch (InvalidPasswordException e) {
            throw new PdfText.PdfException("The PDF is password protected");
        } catch (IOException e) {
            throw new PdfText.PdfException("Not a readable PDF: " + e.getMessage());
        }
    }

    /** Collects the runs and words of one page while PDFBox writes its text. */
    private static final class Collector extends PDFTextStripper {

        /** Text resuming further than this (points) past the end of a blank is in another cell. */
        private static final double AFTER_BLANK = 0.5;

        private final List<Word> runs = new ArrayList<>();
        private final List<Word> words = new ArrayList<>();

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            super.writeString(text, positions);
            add(positions, runs, false);
            add(positions, words, true);
        }

        /**
         * The positions as runs, or split at every blank into words. A run also ends where the text resumes past the
         * end of a blank: some PDFs write a whole line of a table's header as one string, each column placed apart,
         * while the next word after a blank starts where the blank ends (NCKL May 2026: "Saham Bulan" followed 1.0
         * point past its blank by the next column's "Saham Bulan", "Nama" by "Alamat" 35 points past it).
         */
        private static void add(List<TextPosition> positions, List<Word> out, boolean split) {
            StringBuilder text = new StringBuilder();
            double x0 = 0;
            double x1 = 0;
            double y = 0;
            double blankEnd = Double.NaN;
            for (TextPosition t : positions) {
                String unicode = t.getUnicode();
                boolean blank = unicode == null || unicode.isBlank();
                if (blank && split) {
                    flush(text, x0, x1, y, out);
                    text.setLength(0);
                    continue;
                }
                if (!blank && !text.isEmpty() && !Double.isNaN(blankEnd) && t.getXDirAdj() - blankEnd > AFTER_BLANK) {
                    flush(text, x0, x1, y, out);
                    text.setLength(0);
                }
                blankEnd = blank ? t.getXDirAdj() + t.getWidthDirAdj() : Double.NaN;
                if (text.isEmpty()) {
                    if (blank) {
                        continue;    // no leading blank
                    }
                    x0 = t.getXDirAdj();
                    y = t.getYDirAdj();
                }
                text.append(blank ? " " : unicode);
                if (!blank) {
                    x1 = t.getXDirAdj() + t.getWidthDirAdj();
                }
            }
            flush(text, x0, x1, y, out);
        }

        private static void flush(StringBuilder text, double x0, double x1, double y, List<Word> out) {
            String s = text.toString().strip();
            if (!s.isEmpty()) {
                out.add(new Word(x0, x1, y, s));
            }
        }
    }
}
