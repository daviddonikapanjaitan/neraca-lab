package com.neracalab.backend.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/** The text of a PDF, page by page (Apache PDFBox; text in reading order). */
public final class PdfText {

    public static class PdfException extends RuntimeException {

        public PdfException(String message) {
            super(message);
        }
    }

    /** One page: number (1-based) and text. */
    public record Page(int number, String text) {
    }

    private PdfText() {
    }

    /**
     * @throws PdfException not a PDF, password protected, or without any text (a scanned document needs OCR first)
     */
    public static List<Page> pages(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<Page> pages = new ArrayList<>();
            int total = document.getNumberOfPages();
            for (int p = 1; p <= total; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(new Page(p, stripper.getText(document)));
            }
            if (pages.stream().allMatch(page -> page.text().isBlank())) {
                throw new PdfException("The PDF has no text layer (a scanned document): nothing to index");
            }
            return pages;
        } catch (InvalidPasswordException e) {
            throw new PdfException("The PDF is password protected");
        } catch (IOException e) {
            throw new PdfException("Not a readable PDF: " + e.getMessage());
        }
    }
}
