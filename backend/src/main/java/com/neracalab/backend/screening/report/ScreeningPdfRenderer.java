package com.neracalab.backend.screening.report;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.ScreeningViews.AgentScoreView;
import com.neracalab.backend.screening.ScreeningViews.CandidateView;
import com.neracalab.backend.screening.ScreeningViews.RunSummary;
import com.neracalab.backend.screening.ScreeningViews.ScreeningReport;
import com.neracalab.backend.screening.ScreeningViews.UsageRow;

import tools.jackson.databind.JsonNode;

/**
 * The screening report as PDF (A4 landscape, OpenPDF): summary, ranking, one section per selected
 * stock (thesis, news, every agent's score and reasoning), the shortlist, methodology and token usage.
 */
@Component
public class ScreeningPdfRenderer {

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    /** Points of page height a stock section needs at least before a page break (about 8 lines). */
    private static final float MIN_SECTION_SPACE = 170;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

    private static final Color ACCENT = new Color(30, 64, 120);
    private static final Color MUTED = new Color(100, 110, 125);
    private static final Color HEADER_BG = new Color(232, 237, 245);
    private static final Color ROW_ALT = new Color(247, 249, 252);
    private static final Color GOOD = new Color(22, 128, 61);
    private static final Color MID = new Color(161, 98, 7);
    private static final Color BAD = new Color(185, 28, 28);

    private static final Font TITLE = new Font(Font.HELVETICA, 18, Font.BOLD, ACCENT);
    private static final Font H1 = new Font(Font.HELVETICA, 13, Font.BOLD, ACCENT);
    private static final Font H2 = new Font(Font.HELVETICA, 10.5f, Font.BOLD, Color.BLACK);
    private static final Font BODY = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK);
    private static final Font SMALL = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, Color.BLACK);
    private static final Font SMALL_BOLD = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.BLACK);
    private static final Font SMALL_MUTED = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, MUTED);
    private static final Font MUTED_BODY = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, MUTED);

    public byte[] render(ScreeningReport report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 36, 36, 36, 40);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new Footer());
        RunSummary run = report.run();
        doc.addTitle("Screening " + run.exchange() + " " + run.marketCapTier().label() + " top " + run.topN());
        doc.addCreator("Neraca Lab");
        doc.open();

        header(doc, report);
        summary(doc, report);
        List<CandidateView> selected = report.candidates().stream().filter(CandidateView::selected).toList();
        ranking(doc, selected, run.agents());
        for (CandidateView c : selected) {
            // a stock section starts on a new page when less than its heading, thesis and a few rows fit
            if (writer.getVerticalPosition(true) - doc.bottom() < MIN_SECTION_SPACE) {
                doc.newPage();
            }
            stock(doc, c, run.agents());
        }
        List<CandidateView> others = report.candidates().stream().filter(c -> !c.selected()).toList();
        if (!others.isEmpty()) {
            doc.newPage();
            heading(doc, "Rest of the shortlist (not selected)");
            ranking(doc, others, run.agents());
        }
        methodology(doc, report);
        usage(doc, report);
        disclaimer(doc);
        doc.close();
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ sections

    private void header(Document doc, ScreeningReport report) {
        RunSummary run = report.run();
        doc.add(new Paragraph("Neraca Lab - AI Stock Screening Report", TITLE));
        String agents = String.join(", ", run.agents().stream().map(InvestorAgent::label).toList());
        Paragraph p = new Paragraph(run.exchange() + " | " + run.marketCapTier().label() + " | top " + run.topN()
                + " | agents: " + agents, BODY);
        p.setSpacingBefore(4);
        doc.add(p);
        String by = run.createdBy() == null ? "" : " by " + run.createdBy().username();
        doc.add(new Paragraph("Run " + run.id() + " | requested " + dateTime(run.requestedAt()) + by
                + " | finished " + dateTime(run.finishedAt()) + " | market data of " + nvl(run.snapshotDate())
                + " | status " + run.status(), MUTED_BODY));
        String funnel = "Universe " + nvl(run.universeCount()) + " -> eligible " + nvl(run.eligibleCount())
                + " -> shortlist " + nvl(run.shortlistCount()) + " -> selected " + nvl(run.selectedCount());
        String cost = String.format(Locale.ROOT, "Cost $%.4f of $%.2f budget | %,d prompt + %,d completion tokens | %d model calls",
                run.costUsd(), run.budgetUsd(), run.promptTokens(), run.completionTokens(), run.modelCalls());
        Paragraph stats = new Paragraph(funnel + "    " + cost, SMALL_BOLD);
        stats.setSpacingBefore(4);
        stats.setSpacingAfter(8);
        doc.add(stats);
        if (run.message() != null && !run.message().isBlank()) {
            Paragraph m = new Paragraph("Note: " + run.message(), new Font(Font.HELVETICA, 8.5f, Font.ITALIC, MID));
            m.setSpacingAfter(6);
            doc.add(m);
        }
    }

    private void summary(Document doc, ScreeningReport report) {
        JsonNode syn = report.synthesis();
        if (syn == null || syn.isNull()) {
            return;
        }
        heading(doc, "Executive summary");
        String model = text(syn.path("model"));
        doc.add(new Paragraph(nvl(text(syn.path("executiveSummary"))), BODY));
        JsonNode notes = syn.path("portfolioNotes");
        if (notes.isArray() && !notes.isEmpty()) {
            for (JsonNode n : notes) {
                Paragraph item = new Paragraph("- " + n.asString(""), BODY);
                item.setIndentationLeft(8);
                doc.add(item);
            }
        }
        Paragraph by = new Paragraph(model != null ? "Synthesis: " + model
                : "Synthesis: deterministic fallback (" + nvl(text(syn.path("fallback"))) + ")", SMALL_MUTED);
        by.setSpacingAfter(6);
        doc.add(by);
    }

    private void ranking(Document doc, List<CandidateView> candidates, List<InvestorAgent> agents) {
        if (candidates.isEmpty()) {
            doc.add(new Paragraph("No candidates.", BODY));
            return;
        }
        boolean selected = candidates.get(0).selected();
        if (selected) {
            heading(doc, "Final ranking");
        }
        List<Float> widths = new ArrayList<>(List.of(3f, 6f, 18f, 12f, 6f));
        agents.forEach(a -> widths.add(5.5f));
        widths.add(7f);
        PdfPTable table = new PdfPTable(widths.size());
        table.setWidthPercentage(100);
        float[] w = new float[widths.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = widths.get(i);
        }
        table.setWidths(w);
        table.setHeaderRows(1);
        for (String h : header(agents)) {
            table.addCell(headerCell(h));
        }
        int row = 0;
        for (CandidateView c : candidates) {
            Color bg = row++ % 2 == 1 ? ROW_ALT : Color.WHITE;
            table.addCell(cell(c.finalRank() == null ? "-" : String.valueOf(c.finalRank()), SMALL, bg, Element.ALIGN_RIGHT));
            table.addCell(cell(c.ticker(), SMALL_BOLD, bg, Element.ALIGN_LEFT));
            table.addCell(cell(c.companyName(), SMALL, bg, Element.ALIGN_LEFT));
            table.addCell(cell(nvl(c.sector()), SMALL, bg, Element.ALIGN_LEFT));
            table.addCell(scoreCell(c.overallScore(), bg, true));
            for (InvestorAgent agent : agents) {
                AgentScoreView s = c.agents().stream().filter(a -> a.agent() == agent).findFirst().orElse(null);
                table.addCell(scoreCell(s == null ? null : s.finalScore(), bg, false));
            }
            table.addCell(cell(nvl(c.conviction()), SMALL, bg, Element.ALIGN_CENTER));
        }
        table.setSpacingAfter(8);
        doc.add(table);
    }

    private static List<String> header(List<InvestorAgent> agents) {
        List<String> h = new ArrayList<>(List.of("#", "Ticker", "Company", "Sector", "Overall"));
        agents.forEach(a -> h.add(shortLabel(a)));
        h.add("Conviction");
        return h;
    }

    private void stock(Document doc, CandidateView c, List<InvestorAgent> agents) {
        Paragraph title = new Paragraph();
        title.add(new Chunk(c.finalRank() + ". " + c.ticker() + " - " + c.companyName(), H2));
        title.add(new Chunk("   " + nvl(c.sector()) + (c.industry() == null ? "" : " / " + c.industry())
                + String.format(Locale.ROOT, "   overall %.1f", c.overallScore() == null ? 0 : c.overallScore())
                + (c.synthesisAdjustment() == null ? "" : String.format(Locale.ROOT, " (synthesis %+.0f)", c.synthesisAdjustment()))
                + (c.conviction() == null ? "" : "   conviction " + c.conviction()), MUTED_BODY));
        title.setSpacingBefore(10);
        title.setKeepTogether(true);
        doc.add(title);
        if (c.thesis() != null) {
            doc.add(new Paragraph(c.thesis(), BODY));
        }
        if (c.redFlags() != null && c.redFlags().isArray() && !c.redFlags().isEmpty()) {
            List<String> flags = new ArrayList<>();
            c.redFlags().forEach(f -> flags.add(f.asString("")));
            doc.add(new Paragraph("Red flags: " + String.join("; ", flags), new Font(Font.HELVETICA, 8.5f, Font.BOLD, BAD)));
        }
        JsonNode brief = c.news() == null ? null : c.news().path("brief");
        if (brief != null && !brief.isMissingNode()) {
            String news = "News (" + text(brief.path("sentiment")) + "): " + nvl(text(brief.path("summary")));
            List<String> extra = new ArrayList<>();
            brief.path("catalysts").forEach(x -> extra.add("+ " + x.asString("")));
            brief.path("risks").forEach(x -> extra.add("- " + x.asString("")));
            if (!extra.isEmpty()) {
                news += "  " + String.join("  ", extra);
            }
            doc.add(new Paragraph(news, new Font(Font.HELVETICA, 8.5f, Font.NORMAL, Color.DARK_GRAY)));
        }
        PdfPTable table = new PdfPTable(new float[] {10, 5, 5, 5, 7, 52, 16});
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setHeaderRows(1);
        for (String h : List.of("Agent", "Quant", "AI", "Final", "Verdict", "Thesis / strengths / concerns", "Reflection")) {
            table.addCell(headerCell(h));
        }
        for (InvestorAgent agent : agents) {
            AgentScoreView s = c.agents().stream().filter(a -> a.agent() == agent).findFirst().orElse(null);
            if (s == null) {
                continue;
            }
            table.addCell(cell(agent.label(), SMALL_BOLD, Color.WHITE, Element.ALIGN_LEFT));
            table.addCell(scoreCell(s.quantScore(), Color.WHITE, false));
            table.addCell(scoreCell(s.llmScore(), Color.WHITE, false));
            table.addCell(scoreCell(s.finalScore(), Color.WHITE, true));
            table.addCell(cell(nvl(s.verdict()), SMALL, Color.WHITE, Element.ALIGN_CENTER));
            StringBuilder t = new StringBuilder(nvl(s.thesis()));
            appendList(t, "Strengths", s.strengths());
            appendList(t, "Concerns", s.concerns());
            table.addCell(cell(t.toString(), SMALL, Color.WHITE, Element.ALIGN_LEFT));
            table.addCell(cell(reflection(s), SMALL_MUTED, Color.WHITE, Element.ALIGN_LEFT));
        }
        doc.add(table);
    }

    private void methodology(Document doc, ScreeningReport report) {
        doc.newPage();
        heading(doc, "Method");
        doc.add(new Paragraph("""
                [Daily ETL] Yahoo Finance market data and fundamentals of every listing -> PostgreSQL. \
                [Stage 1, no AI] filters below, then a quantitative scorecard per investor agent; the best stocks \
                (top N x multiplier) form the shortlist. [Stage 2] a research agent (ReAct with tools: EmitenNews, \
                Pasardana, IDX Channel, Investor.id, Tavily) writes a news brief; each selected investor agent scores \
                every shortlisted stock independently; Reflection: a validator checks each answer against the data and \
                a critic reviews the flagged ones; Reflexion: lessons from earlier runs are added to the prompts. \
                Final agent score = blend of the quantitative and the AI score; overall = average of the investor \
                agents, blended with the Risk agent (safety) when selected. [Synthesis] the top N with alternates are \
                summarised by the synthesis model, which may adjust a score by at most 5 points.""", BODY));
        JsonNode funnel = report.funnel();
        if (funnel != null && funnel.isArray()) {
            PdfPTable table = new PdfPTable(new float[] {70, 15});
            table.setWidthPercentage(60);
            table.setHorizontalAlignment(Element.ALIGN_LEFT);
            table.setSpacingBefore(6);
            table.addCell(headerCell("Stage 1 filter"));
            table.addCell(headerCell("Stocks left"));
            for (JsonNode step : funnel) {
                table.addCell(cell(step.path("label").asString(""), SMALL, Color.WHITE, Element.ALIGN_LEFT));
                table.addCell(cell(String.valueOf(step.path("remaining").asInt()), SMALL, Color.WHITE, Element.ALIGN_RIGHT));
            }
            doc.add(table);
        }
        JsonNode notes = report.notes();
        if (notes != null && !notes.isNull()) {
            List<String> lines = new ArrayList<>();
            notes.path("messages").forEach(m -> lines.add(m.asString("")));
            notes.path("lessonsLearned").forEach(l -> lines.add("Lesson learned (" + l.path("agent").asString("") + "): "
                    + l.path("lesson").asString("")));
            if (!lines.isEmpty()) {
                Paragraph h = new Paragraph("Notes of the run", H2);
                h.setSpacingBefore(8);
                doc.add(h);
                lines.forEach(l -> doc.add(new Paragraph("- " + l, SMALL)));
            }
        }
    }

    private void usage(Document doc, ScreeningReport report) {
        if (report.usage().isEmpty()) {
            return;
        }
        Paragraph h = new Paragraph("Token usage", H2);
        h.setSpacingBefore(10);
        doc.add(h);
        PdfPTable table = new PdfPTable(new float[] {12, 28, 7, 11, 11, 10, 10, 10});
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        for (String col : List.of("Stage", "Model", "Calls", "Prompt", "Completion", "Reasoning", "Cached", "Cost USD")) {
            table.addCell(headerCell(col));
        }
        for (UsageRow u : report.usage()) {
            table.addCell(cell(u.stage(), SMALL, Color.WHITE, Element.ALIGN_LEFT));
            table.addCell(cell(u.model(), SMALL, Color.WHITE, Element.ALIGN_LEFT));
            table.addCell(cell(String.valueOf(u.calls()), SMALL, Color.WHITE, Element.ALIGN_RIGHT));
            table.addCell(cell(String.format(Locale.ROOT, "%,d", u.promptTokens()), SMALL, Color.WHITE, Element.ALIGN_RIGHT));
            table.addCell(cell(String.format(Locale.ROOT, "%,d", u.completionTokens()), SMALL, Color.WHITE, Element.ALIGN_RIGHT));
            table.addCell(cell(String.format(Locale.ROOT, "%,d", u.reasoningTokens()), SMALL, Color.WHITE, Element.ALIGN_RIGHT));
            table.addCell(cell(String.format(Locale.ROOT, "%,d", u.cachedTokens()), SMALL, Color.WHITE, Element.ALIGN_RIGHT));
            table.addCell(cell(String.format(Locale.ROOT, "%.5f%s", u.costUsd(), u.costEstimated() ? "*" : ""), SMALL,
                    Color.WHITE, Element.ALIGN_RIGHT));
        }
        doc.add(table);
        if (report.usage().stream().anyMatch(UsageRow::costEstimated)) {
            doc.add(new Paragraph("* includes calls whose cost was estimated from list prices (the provider reported none).",
                    SMALL_MUTED));
        }
    }

    private void disclaimer(Document doc) {
        Paragraph p = new Paragraph("This report is generated automatically from public data (Yahoo Finance, news sites) "
                + "and AI models. It can contain errors and is not investment advice.", SMALL_MUTED);
        p.setSpacingBefore(12);
        doc.add(p);
    }

    // ------------------------------------------------------------------ helpers

    private void heading(Document doc, String text) {
        Paragraph p = new Paragraph(text, H1);
        p.setSpacingBefore(8);
        p.setSpacingAfter(4);
        doc.add(p);
    }

    private static PdfPCell headerCell(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, SMALL_BOLD));
        c.setBackgroundColor(HEADER_BG);
        c.setPadding(3);
        c.setBorderColor(new Color(210, 216, 226));
        return c;
    }

    private static PdfPCell cell(String text, Font font, Color bg, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setBackgroundColor(bg);
        c.setPadding(3);
        c.setHorizontalAlignment(align);
        c.setBorderColor(new Color(225, 229, 236));
        return c;
    }

    private static PdfPCell scoreCell(Double score, Color bg, boolean bold) {
        if (score == null) {
            return cell("-", SMALL_MUTED, bg, Element.ALIGN_RIGHT);
        }
        Color color = score >= 65 ? GOOD : score >= 45 ? MID : BAD;
        Font font = new Font(Font.HELVETICA, 7.5f, bold ? Font.BOLD : Font.NORMAL, color);
        return cell(String.format(Locale.ROOT, "%.1f", score), font, bg, Element.ALIGN_RIGHT);
    }

    private static void appendList(StringBuilder b, String label, JsonNode list) {
        if (list == null || !list.isArray() || list.isEmpty()) {
            return;
        }
        List<String> items = new ArrayList<>();
        list.forEach(i -> items.add(i.asString("")));
        b.append('\n').append(label).append(": ").append(String.join("; ", items));
    }

    private static String reflection(AgentScoreView s) {
        if ("QUANT_ONLY".equals(s.status())) {
            return "Quantitative score only" + (s.reflection() != null && s.reflection().has("error")
                    ? " (" + s.reflection().path("error").asString("") + ")" : "");
        }
        JsonNode r = s.reflection();
        if (r == null || r.isNull()) {
            return "No issues";
        }
        List<String> issues = new ArrayList<>();
        r.path("issues").forEach(i -> issues.add(i.path("code").asString("")));
        String text = "Flagged: " + String.join(", ", issues);
        if (r.has("revised")) {
            text += r.path("changed").asBoolean(false)
                    ? "; revised " + Math.round(r.path("original").path("score").asDouble()) + " -> "
                    + Math.round(r.path("revised").path("score").asDouble())
                    : "; kept after review";
            if (r.hasNonNull("note")) {
                text += " (" + r.path("note").asString("") + ")";
            }
        }
        return text;
    }

    private static String shortLabel(InvestorAgent agent) {
        return switch (agent) {
            case BUFFETT -> "Buffett";
            case MUNGER -> "Munger";
            case LYNCH -> "Lynch";
            case FISHER -> "Fisher";
            case GILL -> "Gill";
            case RISK -> "Risk";
        };
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString(null);
    }

    private static String nvl(Object value) {
        return value == null ? "-" : value.toString();
    }

    private static String dateTime(java.time.Instant instant) {
        return instant == null ? "-" : DATE_TIME.format(instant.atZone(JAKARTA)) + " WIB";
    }

    /** "Neraca Lab screening report - page n" at the bottom of every page. */
    private static final class Footer extends PdfPageEventHelper {

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Rectangle page = document.getPageSize();
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("Neraca Lab screening report - page " + writer.getPageNumber(), SMALL_MUTED),
                    page.getRight() - 36, page.getBottom() + 20, 0);
        }
    }
}
