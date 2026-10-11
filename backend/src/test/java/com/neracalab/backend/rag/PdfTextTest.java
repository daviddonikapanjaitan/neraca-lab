package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import com.neracalab.backend.rag.controller.RagController;

/** Text of the HRTA financial statement PDFs in data/IDX_XBRL/HRTA/pdf. */
class PdfTextTest {

    static final Path HRTA_PDF = Path.of("..", "data", "IDX_XBRL", "HRTA", "pdf", "FinancialStatement-2025-Tahunan-HRTA.pdf");

    @Test
    void readsTheHrtaAnnualReportPageByPage() throws IOException {
        List<PdfText.Page> pages = PdfText.pages(Files.readAllBytes(HRTA_PDF));
        assertThat(pages).hasSizeGreaterThan(50);
        for (int i = 0; i < pages.size(); i++) {
            assertThat(pages.get(i).number()).isEqualTo(i + 1);
        }
        String all = String.join("\n", pages.stream().map(PdfText.Page::text).toList());
        assertThat(all).containsIgnoringCase("Hartadinata");
        assertThat(all).containsIgnoringCase("2025");

        List<TextChunker.Chunk> chunks = new TextChunker(1500, 200)
                .chunk(pages.stream().map(p -> new TextChunker.Part(p.text(), p.number())).toList());
        assertThat(chunks).hasSizeGreaterThan(100);
        assertThat(chunks.getFirst().pageFrom()).isEqualTo(1);
        assertThat(chunks.getLast().pageTo()).isEqualTo(pages.size());
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(1500));
    }

    @Test
    void rejectsAFileThatIsNotAPdf() {
        byte[] xlsx = "PK\u0003\u0004 not a pdf".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> PdfText.pages(xlsx)).isInstanceOf(PdfText.PdfException.class)
                .hasMessageContaining("Not a readable PDF");
        assertThat(RagController.isPdf(xlsx)).isFalse();
    }

    @Test
    void rejectsAPdfWithoutText() throws IOException {
        byte[] blank;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            blank = out.toByteArray();
        }
        assertThat(RagController.isPdf(blank)).isTrue();
        assertThatThrownBy(() -> PdfText.pages(blank)).isInstanceOf(PdfText.PdfException.class)
                .hasMessageContaining("no text layer");
    }
}
