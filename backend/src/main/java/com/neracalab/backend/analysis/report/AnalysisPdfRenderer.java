package com.neracalab.backend.analysis.report;

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

import com.neracalab.backend.analysis.AnalysisViews.AnalysisReport;
import com.neracalab.backend.analysis.AnalysisViews.AnalysisSummary;
import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.ScreeningViews.AgentScoreView;
import com.neracalab.backend.screening.ScreeningViews.UsageRow;

import tools.jackson.databind.JsonNode;

/**
 * The analysis report as PDF (A4 portrait, OpenPDF): overall view, synthesis, every agent's score and reasoning,
 * the research brief with its sources, key figures, notes and token usage.
 */
@Component
public class AnalysisPdfRenderer {

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);
    /** Key figures of the fact sheet shown per period: section, key, label, ratio (shown as %). */
    private static final List<String[]> FIGURES = List.of(
            new String[] {"income", "revenue", "Revenue", ""},
            new String[] {"income", "netIncomeToParent", "Net income to parent", ""},
            new String[] {"balance", "totalAssets", "Total assets", ""},
            new String[] {"balance", "totalEquity", "Total equity", ""},
            new String[] {"metrics", "roe_annualized", "ROE (annualized)", "%"},
            new String[] {"metrics", "net_margin", "Net margin", "%"},
            new String[] {"metrics", "operating_margin", "Operating margin", "%"},
            new String[] {"metrics", "debt_to_equity", "Debt / equity", ""},
            new String[] {"metrics", "current_ratio", "Current ratio", ""});

    private static final Color ACCENT = new Color(30, 64, 120);
    private static final Color MUTED = new Color(100, 110, 125);
    private static final Color HEADER_BG = new Color(232, 237, 245);
    private static final Color GOOD = new Color(22, 128, 61);
    private static final Color MID = new Color(161, 98, 7);
    private static final Color BAD = new Color(185, 28, 28);

    private static final Font TITLE = new Font(Font.HELVETICA, 17, Font.BOLD, ACCENT);
    private static final Font H1 = new Font(Font.HELVETICA, 12.5f, Font.BOLD, ACCENT);
    private static final Font H2 = new Font(Font.HELVETICA, 10, Font.BOLD, Color.BLACK);
    private static final Font BODY = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK);
    private static final Font SMALL = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, Color.BLACK);
    private static final Font SMALL_BOLD = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.BLACK);
    private static final Font SMALL_MUTED = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, MUTED);
    private static final Font MUTED_BODY = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, MUTED);

    public byte[] render(AnalysisReport report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 36, 40);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new Footer());
        AnalysisSummary run = report.run();
        doc.addTitle("Analysis " + run.exchange() + " " + run.ticker());
        doc.addCreator("Neraca Lab");
        doc.open();

        header(doc, report);
        synthesis(doc, report);
        agents(doc, report);
        research(doc, report.research());
        figures(doc, report.context());
        notes(doc, report.notes());
        usage(doc, report.usage());
        Paragraph p = new Paragraph("This report is generated automatically from the company's stored filings, prices "
                + "and news, Yahoo Finance data and AI models. It can contain errors and is not investment advice.",
                SMALL_MUTED);
        p.setSpacingBefore(12);
        doc.add(p);
        doc.close();
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ sections

    private void header(Document doc, AnalysisReport report) {
        AnalysisSummary run = report.run();
        doc.add(new Paragraph("Neraca Lab - AI Stock Analysis", TITLE));
        Paragraph company = new Paragraph(run.ticker() + " - " + nvl(run.companyName()) + " (" + run.exchange() + ")", H1);
        company.setSpacingBefore(2);
        doc.add(company);
        String agents = String.join(", ", run.agents().stream().map(InvestorAgent::label).toList());
        doc.add(new Paragraph("Agents: " + agents, BODY));
        String by = run.createdBy() == null ? "" : " by " + run.createdBy().username();
        doc.add(new Paragraph("Analysis " + run.id() + " | requested " + dateTime(run.requestedAt()) + by + " | finished "
                + dateTime(run.finishedAt()) + " | market data of " + nvl(run.marketDataDate()) + " | status "
                + run.status(), MUTED_BODY));
        Paragraph score = new Paragraph();
        score.setSpacingBefore(6);
        score.add(new Chunk("Overall score " + (run.overallScore() == null ? "-"
                : String.format(Locale.ROOT, "%.1f", run.overallScore())), new Font(Font.HELVETICA, 12, Font.BOLD,
                color(run.overallScore()))));
        score.add(new Chunk("   " + nvl(run.verdict()) + (run.conviction() == null ? "" : "   conviction " + run.conviction())
                + (report.quantOverall() == null ? "" : String.format(Locale.ROOT, "   quantitative %.1f", report.quantOverall()))
                + (report.synthesisAdjustment() == null ? ""
                : String.format(Locale.ROOT, "   synthesis %+.0f", report.synthesisAdjustment())), BODY));
        doc.add(score);
        String cost = String.format(Locale.ROOT, "Cost $%.4f of $%.2f budget | %,d prompt + %,d completion tokens | %d calls",
                run.costUsd(), run.budgetUsd(), run.promptTokens(), run.completionTokens(), run.modelCalls());
        Paragraph c = new Paragraph(cost, SMALL_BOLD);
        c.setSpacingAfter(6);
        doc.add(c);
        if (run.message() != null && !run.message().isBlank()) {
            Paragraph m = new Paragraph("Note: " + run.message(), new Font(Font.HELVETICA, 8.5f, Font.ITALIC, MID));
            m.setSpacingAfter(6);
            doc.add(m);
        }
    }

    private void synthesis(Document doc, AnalysisReport report) {
        JsonNode syn = report.synthesis();
        if (syn == null || syn.isNull()) {
            return;
        }
        heading(doc, "Executive summary");
        doc.add(new Paragraph(nvl(text(syn.path("executiveSummary"))), BODY));
        if (text(syn.path("thesis")) != null) {
            Paragraph t = new Paragraph("Thesis: " + text(syn.path("thesis")), new Font(Font.HELVETICA, 9, Font.BOLD, Color.BLACK));
            t.setSpacingBefore(4);
            doc.add(t);
        }
        list(doc, "Bull case", syn.path("bullCase"), GOOD);
        list(doc, "Bear case", syn.path("bearCase"), BAD);
        list(doc, "Key risks", syn.path("keyRisks"), Color.BLACK);
        list(doc, "Monitor", syn.path("monitor"), Color.BLACK);
        list(doc, "Data gaps", syn.path("dataGaps"), MUTED);
        if (text(syn.path("adjustmentReason")) != null) {
            doc.add(new Paragraph("Score adjustment: " + text(syn.path("adjustmentReason")), MUTED_BODY));
        }
        String model = text(syn.path("model"));
        Paragraph by = new Paragraph(model != null ? "Synthesis: " + model
                : "Synthesis: deterministic fallback (" + nvl(text(syn.path("fallback"))) + ")", SMALL_MUTED);
        by.setSpacingAfter(4);
        doc.add(by);
    }

    private void agents(Document doc, AnalysisReport report) {
        if (report.agents().isEmpty()) {
            return;
        }
        heading(doc, "Investor agents");
        PdfPTable table = new PdfPTable(new float[] {13, 6, 6, 6, 9, 42, 18});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String h : List.of("Agent", "Quant", "AI", "Final", "Verdict", "Thesis / strengths / concerns", "Reflection")) {
            table.addCell(headerCell(h));
        }
        for (AgentScoreView s : report.agents()) {
            table.addCell(cell(s.label(), SMALL_BOLD, Element.ALIGN_LEFT));
            table.addCell(scoreCell(s.quantScore(), false));
            table.addCell(scoreCell(s.llmScore(), false));
            table.addCell(scoreCell(s.finalScore(), true));
            table.addCell(cell(nvl(s.verdict()), SMALL, Element.ALIGN_CENTER));
            StringBuilder t = new StringBuilder(nvl(s.thesis()));
            appendList(t, "Strengths", s.strengths());
            appendList(t, "Concerns", s.concerns());
            table.addCell(cell(t.toString(), SMALL, Element.ALIGN_LEFT));
            table.addCell(cell(reflection(s), SMALL_MUTED, Element.ALIGN_LEFT));
        }
        doc.add(table);
    }

    private void research(Document doc, JsonNode research) {
        if (research == null || research.isNull()) {
            return;
        }
        JsonNode brief = research.path("brief");
        heading(doc, "Research brief (company filings and news)");
        field(doc, "Business", brief.path("business"));
        field(doc, "Moat", brief.path("moat"));
        field(doc, "Management", brief.path("management"));
        field(doc, "Growth", brief.path("growth"));
        list(doc, "Risks", brief.path("risks"), BAD);
        list(doc, "Catalysts", brief.path("catalysts"), GOOD);
        if (text(brief.path("newsSummary")) != null) {
            field(doc, "News (" + nvl(text(brief.path("newsSentiment"))) + ")", brief.path("newsSummary"));
        }
        JsonNode evidence = brief.path("evidence");
        if (evidence.isArray() && !evidence.isEmpty()) {
            Paragraph h = new Paragraph("Evidence", H2);
            h.setSpacingBefore(4);
            doc.add(h);
            for (JsonNode e : evidence) {
                doc.add(new Paragraph("[" + e.path("ref").asString("") + "] " + e.path("fact").asString(""), SMALL));
            }
        }
        JsonNode retrieved = research.path("retrieved");
        if (retrieved.isArray() && !retrieved.isEmpty()) {
            PdfPTable table = new PdfPTable(new float[] {6, 8, 50, 14, 22});
            table.setWidthPercentage(100);
            table.setSpacingBefore(4);
            table.setHeaderRows(1);
            for (String h : List.of("Ref", "Source", "Document / article", "Where", "Query")) {
                table.addCell(headerCell(h));
            }
            for (JsonNode r : retrieved) {
                table.addCell(cell(r.path("ref").asString(""), SMALL_BOLD, Element.ALIGN_LEFT));
                table.addCell(cell(r.path("source").asString(""), SMALL, Element.ALIGN_LEFT));
                table.addCell(cell(r.path("title").asString(""), SMALL, Element.ALIGN_LEFT));
                table.addCell(cell(nvl(text(r.path("where"))), SMALL, Element.ALIGN_LEFT));
                table.addCell(cell(r.path("query").asString(""), SMALL_MUTED, Element.ALIGN_LEFT));
            }
            doc.add(table);
        }
        if (text(research.path("note")) != null) {
            doc.add(new Paragraph("Note: " + text(research.path("note")), SMALL_MUTED));
        }
    }

    private void figures(Document doc, JsonNode context) {
        JsonNode periods = context == null ? null : context.path("factSheet").path("periods");
        if (periods == null || !periods.isArray() || periods.isEmpty()) {
            return;
        }
        heading(doc, "Key figures (" + nvl(text(context.path("factSheet").path("amountUnit"))) + ")");
        float[] widths = new float[periods.size() + 1];
        widths[0] = 22;
        for (int i = 1; i < widths.length; i++) {
            widths[i] = 78f / periods.size();
        }
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.addCell(headerCell(""));
        for (JsonNode p : periods) {
            table.addCell(headerCell(p.path("period").asString("")));
        }
        for (String[] f : FIGURES) {
            table.addCell(cell(f[2], SMALL_BOLD, Element.ALIGN_LEFT));
            for (JsonNode p : periods) {
                JsonNode v = p.path(f[0]).path(f[1]);
                String value = v.isNumber() ? (f[3].equals("%")
                        ? String.format(Locale.ROOT, "%.1f%%", v.asDouble() * 100)
                        : String.format(Locale.ROOT, "%,.2f", v.asDouble())) : "-";
                table.addCell(cell(value, SMALL, Element.ALIGN_RIGHT));
            }
        }
        doc.add(table);
    }

    private void notes(Document doc, JsonNode notes) {
        if (notes == null || notes.isNull()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        notes.path("messages").forEach(m -> lines.add(m.asString("")));
        notes.path("lessonsLearned").forEach(l -> lines.add("Lesson learned (" + l.path("agent").asString("") + "): "
                + l.path("lesson").asString("")));
        if (!lines.isEmpty()) {
            heading(doc, "Notes");
            lines.forEach(l -> doc.add(new Paragraph("- " + l, SMALL)));
        }
    }

    private void usage(Document doc, List<UsageRow> usage) {
        if (usage.isEmpty()) {
            return;
        }
        heading(doc, "Token usage");
        PdfPTable table = new PdfPTable(new float[] {14, 30, 7, 12, 12, 11, 14});
        table.setWidthPercentage(100);
        for (String col : List.of("Stage", "Model", "Calls", "Prompt", "Completion", "Cached", "Cost USD")) {
            table.addCell(headerCell(col));
        }
        for (UsageRow u : usage) {
            table.addCell(cell(u.stage(), SMALL, Element.ALIGN_LEFT));
            table.addCell(cell(u.model(), SMALL, Element.ALIGN_LEFT));
            table.addCell(cell(String.valueOf(u.calls()), SMALL, Element.ALIGN_RIGHT));
            table.addCell(cell(String.format(Locale.ROOT, "%,d", u.promptTokens()), SMALL, Element.ALIGN_RIGHT));
            table.addCell(cell(String.format(Locale.ROOT, "%,d", u.completionTokens()), SMALL, Element.ALIGN_RIGHT));
            table.addCell(cell(String.format(Locale.ROOT, "%,d", u.cachedTokens()), SMALL, Element.ALIGN_RIGHT));
            table.addCell(cell(String.format(Locale.ROOT, "%.5f%s", u.costUsd(), u.costEstimated() ? "*" : ""), SMALL,
                    Element.ALIGN_RIGHT));
        }
        doc.add(table);
        if (usage.stream().anyMatch(UsageRow::costEstimated)) {
            doc.add(new Paragraph("* includes calls whose cost was estimated from list prices (the provider reported none).",
                    SMALL_MUTED));
        }
    }

    // ------------------------------------------------------------------ helpers

    private void heading(Document doc, String text) {
        Paragraph p = new Paragraph(text, H1);
        p.setSpacingBefore(10);
        p.setSpacingAfter(4);
        doc.add(p);
    }

    private static void field(Document doc, String label, JsonNode value) {
        String text = text(value);
        if (text == null || text.isBlank()) {
            return;
        }
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + ": ", new Font(Font.HELVETICA, 9, Font.BOLD, Color.BLACK)));
        p.add(new Chunk(text, BODY));
        doc.add(p);
    }

    private static void list(Document doc, String label, JsonNode items, Color color) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            return;
        }
        Paragraph h = new Paragraph(label, new Font(Font.HELVETICA, 9, Font.BOLD, color));
        h.setSpacingBefore(3);
        doc.add(h);
        for (JsonNode item : items) {
            Paragraph p = new Paragraph("- " + item.asString(""), BODY);
            p.setIndentationLeft(8);
            doc.add(p);
        }
    }

    private static PdfPCell headerCell(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, SMALL_BOLD));
        c.setBackgroundColor(HEADER_BG);
        c.setPadding(3);
        c.setBorderColor(new Color(210, 216, 226));
        return c;
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setPadding(3);
        c.setHorizontalAlignment(align);
        c.setBorderColor(new Color(225, 229, 236));
        return c;
    }

    private static PdfPCell scoreCell(Double score, boolean bold) {
        if (score == null) {
            return cell("-", SMALL_MUTED, Element.ALIGN_RIGHT);
        }
        Font font = new Font(Font.HELVETICA, 7.5f, bold ? Font.BOLD : Font.NORMAL, color(score));
        return cell(String.format(Locale.ROOT, "%.1f", score), font, Element.ALIGN_RIGHT);
    }

    private static Color color(Double score) {
        if (score == null) {
            return MUTED;
        }
        return score >= 65 ? GOOD : score >= 45 ? MID : BAD;
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
        if ("QUANT_ONLY".equals(s.status()) || "NO_SCORE".equals(s.status())) {
            String what = "QUANT_ONLY".equals(s.status()) ? "Quantitative score only" : "No score";
            return what + (s.reflection() != null && s.reflection().has("error")
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

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString(null);
    }

    private static String nvl(Object value) {
        return value == null ? "-" : value.toString();
    }

    private static String dateTime(java.time.Instant instant) {
        return instant == null ? "-" : DATE_TIME.format(instant.atZone(JAKARTA)) + " WIB";
    }

    /** "Neraca Lab analysis report - page n" at the bottom of every page. */
    private static final class Footer extends PdfPageEventHelper {

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Rectangle page = document.getPageSize();
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("Neraca Lab analysis report - page " + writer.getPageNumber(), SMALL_MUTED),
                    page.getRight() - 36, page.getBottom() + 20, 0);
        }
    }
}
