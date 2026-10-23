package com.neracalab.backend.registration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * A monthly registration report as idx.co.id prints it, small: a cover page, the directors' and commissioners' table
 * (each header and cell line centred in its column, as in the real reports) and the free float line with the shares
 * listed. For the tests that must not touch the real HRTA reports stored in the database.
 */
final class LbrePdf {

    /** A row of the table: name, position, shares and percentages as printed ("2.157.500", "0,05%"), affiliate mark. */
    record Row(String name, String position, String previous, String previousPct, String current, String currentPct,
               boolean affiliate) {
    }

    private static final double[] CENTERS = {132, 195, 264, 331, 387, 440, 497, 553, 609};
    private static final float PAGE_HEIGHT = 595;

    private final PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final String ticker;
    private final String month;
    private final int year;
    private final String filedAt;
    private final boolean correction;
    private final List<Row> rows = new ArrayList<>();

    /**
     * @param month   Indonesian month name ("September")
     * @param filedAt "06-10-2026 09:30" (as printed: day-month-year)
     */
    LbrePdf(String ticker, String month, int year, String filedAt, boolean correction) {
        this.ticker = ticker;
        this.month = month;
        this.year = year;
        this.filedAt = filedAt;
        this.correction = correction;
    }

    LbrePdf row(String name, String position, String previous, String previousPct, String current, String currentPct,
                boolean affiliate) {
        rows.add(new Row(name, position, previous, previousPct, current, currentPct, affiliate));
        return this;
    }

    byte[] bytes() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDRectangle size = new PDRectangle(1000, PAGE_HEIGHT);
            PDPage cover = new PDPage(size);
            document.addPage(cover);
            try (PDPageContentStream c = new PDPageContentStream(document, cover)) {
                double y = 40;
                for (String line : List.of("Go To English Page", "Nomor Surat 001/" + ticker + "/X/" + year,
                        "Nama Perusahaan PT Uji " + ticker + " Tbk", "Kode Emiten " + ticker, "Papan Pencatatan Utama",
                        "Perihal Laporan Bulanan Registrasi Pemegang Efek" + (correction ? " (KOREKSI)" : ""),
                        "Perseroan menyampaikan Laporan Bulanan Registrasi Pemegang Efek yang berakhir pada akhir bulan "
                                + month + " -", year + " Sebagai Berikut :", "Biro Administrasi Efek: PT. Biro Uji")) {
                    text(c, 36, y, 9, line);
                    y += 14;
                }
            }
            PDPage table = new PDPage(size);
            document.addPage(table);
            try (PDPageContentStream c = new PDPageContentStream(document, table)) {
                centred(c, 0, 66, "Nama");
                centred(c, 1, 66, "Alamat");
                centred(c, 2, 66, "Jabatan");
                centred(c, 3, 60, "Jumlah Saham");
                centred(c, 3, 71, "Sebelumnya");
                centred(c, 4, 49, "Persen");
                centred(c, 4, 60, "Saham");
                centred(c, 4, 71, "Sebelumnya");
                centred(c, 5, 54, "Jumlah");
                centred(c, 5, 66, "Saham Bulan");
                centred(c, 5, 77, "Ini");
                centred(c, 6, 54, "Persen");
                centred(c, 6, 66, "Saham Bulan");
                centred(c, 6, 77, "Ini");
                centred(c, 7, 66, "Pengendali");
                centred(c, 8, 60, "Afiliasi");
                centred(c, 8, 72, "Pengendali");
                text(c, 42, 120, 6, "Pemegang");
                text(c, 42, 132, 6, "Saham");
                double y = 96;
                for (Row r : rows) {
                    centred(c, 0, y, r.name());
                    centred(c, 1, y, "Jl. Uji No. 1");
                    centred(c, 1, y + 11.3, "Jakarta");
                    centred(c, 2, y, r.position());
                    centred(c, 3, y, r.previous());
                    centred(c, 4, y, r.previousPct());
                    centred(c, 5, y, r.current());
                    centred(c, 6, y, r.currentPct());
                    if (r.affiliate()) {
                        centred(c, 8, y + 6, "X");
                    }
                    y += 34;
                }
            }
            PDPage freeFloat = new PDPage(size);
            document.addPage(freeFloat);
            try (PDPageContentStream c = new PDPageContentStream(document, freeFloat)) {
                text(c, 36, 40, 9, "Jumlah saham tercatat di Bursa per akhir bulan 1.000.000.000 1.000.000.000");
                text(c, 36, 60, 9, "Tanggal dan Waktu " + filedAt);
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Text centred in column {@code col} with its baseline {@code y} points below the top. */
    private void centred(PDPageContentStream c, int col, double y, String s) throws IOException {
        if (s == null || s.isEmpty()) {
            return;
        }
        double width = font.getStringWidth(s) / 1000 * 5;
        text(c, CENTERS[col] - width / 2, y, 5, s);
    }

    private void text(PDPageContentStream c, double x, double y, float size, String s) throws IOException {
        c.beginText();
        c.setFont(font, size);
        c.newLineAtOffset((float) x, (float) (PAGE_HEIGHT - y));
        c.showText(s);
        c.endText();
    }
}
