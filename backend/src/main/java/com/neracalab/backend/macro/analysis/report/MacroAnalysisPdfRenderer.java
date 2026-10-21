package com.neracalab.backend.macro.analysis.report;

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

import com.neracalab.backend.macro.analysis.MacroAgent;
import com.neracalab.backend.macro.analysis.MacroAnalysisViews.MacroAgentView;
import com.neracalab.backend.macro.analysis.MacroAnalysisViews.MacroAnalysisReport;
import com.neracalab.backend.macro.analysis.MacroAnalysisViews.MacroAnalysisSummary;
import com.neracalab.backend.screening.ScreeningViews.UsageRow;

import tools.jackson.databind.JsonNode;

/**
 * The macro analysis report as PDF (A4 portrait, OpenPDF): investability score and verdict, synthesis with sector tilts,
 * every macro agent's score and reasoning, the research brief with its tool results, the rules' conclusion and regimes,
 * the commodities and currency pairs, notes and token usage.
 */
@Component
public class MacroAnalysisPdfRenderer {

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

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

    public byte[] render(MacroAnalysisReport report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 36, 40);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new Footer());
        MacroAnalysisSummary run = report.run();
        doc.addTitle("Macro analysis " + run.country() + " " + run.indexCode());
        doc.addCreator("Neraca Lab");
        doc.open();

        header(doc, report);
        synthesis(doc, report);
        agents(doc, report);
        research(doc, report.research());
        rules(doc, report.context());
        markets(doc, report.context());
        notes(doc, report.notes());
        usage(doc, report.usage());
        Paragraph p = new Paragraph("This report is generated automatically from the stored macro series, index, commodity "
                + "and currency data, fixed rules and AI models. Macro data lag the market. It can contain errors and is not "
                + "investment advice.", SMALL_MUTED);
        p.setSpacingBefore(12);
        doc.add(p);
        doc.close();
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ sections

    private void header(Document doc, MacroAnalysisReport report) {
        MacroAnalysisSummary run = report.run();
        doc.add(new Paragraph("Neraca Lab - AI Macro Analysis", TITLE));
        Paragraph subject = new Paragraph("Is " + nvl(run.countryName()) + " investable? " + nvl(run.indexName()) + " ("
                + run.indexCode() + ")", H1);
        subject.setSpacingBefore(2);
        doc.add(subject);
        doc.add(new Paragraph("Agents: " + String.join(", ", run.agents().stream().map(MacroAgent::label).toList()), BODY));
        String by = run.createdBy() == null ? "" : " by " + run.createdBy().username();
        doc.add(new Paragraph("Analysis " + run.id() + " | requested " + dateTime(run.requestedAt()) + by + " | finished "
                + dateTime(run.finishedAt()) + " | data as of " + nvl(run.asOfDate()) + " | status " + run.status(),
                MUTED_BODY));
        Paragraph score = new Paragraph();
        score.setSpacingBefore(6);
        score.add(new Chunk("Investability " + (run.overallScore() == null ? "-"
                : String.format(Locale.ROOT, "%.1f", run.overallScore())) + "/100", new Font(Font.HELVETICA, 12, Font.BOLD,
                color(run.overallScore()))));
        score.add(new Chunk("   " + verdictLabel(run.verdict()) + (run.action() == null ? "" : "   action " + run.action())
                + (run.conviction() == null ? "" : "   conviction " + run.conviction())
                + (run.rulesScore() == null ? "" : String.format(Locale.ROOT, "   rules %.0f", run.rulesScore()))
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

    private void synthesis(Document doc, MacroAnalysisReport report) {
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
        JsonNode tilts = syn.path("sectorTilts");
        if (tilts.isArray() && !tilts.isEmpty()) {
            Paragraph h = new Paragraph("Sector tilts", new Font(Font.HELVETICA, 9, Font.BOLD, Color.BLACK));
            h.setSpacingBefore(3);
            doc.add(h);
            for (JsonNode t : tilts) {
                String tilt = t.path("tilt").asString("NEUTRAL");
                Paragraph p = new Paragraph();
                p.setIndentationLeft(8);
                p.add(new Chunk(t.path("sector").asString("") + ": " + tilt.toLowerCase(Locale.ROOT), new Font(Font.HELVETICA, 9,
                        Font.BOLD, "OVERWEIGHT".equals(tilt) ? GOOD : "UNDERWEIGHT".equals(tilt) ? BAD : MUTED)));
                if (text(t.path("why")) != null) {
                    p.add(new Chunk(" - " + text(t.path("why")), BODY));
                }
                doc.add(p);
            }
        }
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

    private void agents(Document doc, MacroAnalysisReport report) {
        if (report.agents().isEmpty()) {
            return;
        }
        heading(doc, "Macro agents");
        PdfPTable table = new PdfPTable(new float[] {14, 6, 6, 6, 9, 43, 16});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String h : List.of("Agent", "Prior", "AI", "Final", "Stance", "Thesis / supports / risks / triggers", "Reflection")) {
            table.addCell(headerCell(h));
        }
        for (MacroAgentView s : report.agents()) {
            table.addCell(cell(s.label(), SMALL_BOLD, Element.ALIGN_LEFT));
            table.addCell(scoreCell(s.priorScore(), false));
            table.addCell(scoreCell(s.aiScore(), false));
            table.addCell(scoreCell(s.finalScore(), true));
            table.addCell(cell(nvl(s.stance()), SMALL, Element.ALIGN_CENTER));
            StringBuilder t = new StringBuilder(nvl(s.thesis()));
            appendList(t, "Supports", s.supports());
            appendList(t, "Risks", s.risks());
            appendList(t, "Buy when", s.buyWhen());
            appendList(t, "Sell when", s.sellWhen());
            table.addCell(cell(t.toString(), SMALL, Element.ALIGN_LEFT));
            table.addCell(cell(reflection(s), SMALL_MUTED, Element.ALIGN_LEFT));
        }
        doc.add(table);
        doc.add(new Paragraph("Prior = the investor's preset of the rules (0 - 100); final = 60% prior + 40% the agent's "
                + "judgement after the reflection review.", SMALL_MUTED));
    }

    private void research(Document doc, JsonNode research) {
        if (research == null || research.isNull()) {
            return;
        }
        JsonNode brief = research.path("brief");
        heading(doc, "Research brief (stored macro history)");
        field(doc, "Regime", brief.path("regime"));
        field(doc, "Liquidity", brief.path("liquidity"));
        field(doc, "Inflation and rates", brief.path("inflationRates"));
        field(doc, "External", brief.path("external"));
        field(doc, "Growth", brief.path("growth"));
        list(doc, "Risks", brief.path("risks"), BAD);
        list(doc, "Catalysts", brief.path("catalysts"), GOOD);
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
            PdfPTable table = new PdfPTable(new float[] {6, 12, 22, 60});
            table.setWidthPercentage(100);
            table.setSpacingBefore(4);
            table.setHeaderRows(1);
            for (String h : List.of("Ref", "Tool", "Arguments", "Result")) {
                table.addCell(headerCell(h));
            }
            for (JsonNode r : retrieved) {
                table.addCell(cell(r.path("ref").asString(""), SMALL_BOLD, Element.ALIGN_LEFT));
                table.addCell(cell(r.path("tool").asString(""), SMALL, Element.ALIGN_LEFT));
                table.addCell(cell(r.path("arguments").asString(""), SMALL, Element.ALIGN_LEFT));
                table.addCell(cell(r.path("summary").asString(""), SMALL_MUTED, Element.ALIGN_LEFT));
            }
            doc.add(table);
        }
        if (text(research.path("note")) != null) {
            doc.add(new Paragraph("Note: " + text(research.path("note")), SMALL_MUTED));
        }
    }

    private void rules(Document doc, JsonNode context) {
        if (context == null || context.isNull()) {
            return;
        }
        JsonNode conclusion = context.path("rulesConclusion");
        heading(doc, "The rules (Macro page, no AI)");
        if (!conclusion.isMissingNode()) {
            doc.add(new Paragraph("Rules investability " + nvl(text(conclusion.path("score"))) + "/100, "
                    + verdictLabel(text(conclusion.path("verdict"))) + ", " + nvl(text(conclusion.path("actionLabel")))
                    + ": " + nvl(text(conclusion.path("reason"))), BODY));
        }
        JsonNode regimes = context.path("regimes");
        for (String[] key : List.of(new String[] {"liquidity", "Liquidity regime (Druckenmiller)"},
                new String[] {"risk", "Risk regime (Tudor Jones)"})) {
            JsonNode r = regimes.path(key[0]);
            if (r.isMissingNode()) {
                continue;
            }
            doc.add(new Paragraph(key[1] + ": " + nvl(text(r.path("state"))) + " (" + nvl(text(r.path("score"))) + " of "
                    + nvl(text(r.path("signals"))) + "); three months ago " + nvl(text(r.path("stateThen"))), BODY));
        }
        JsonNode index = context.path("index");
        if (!index.isMissingNode()) {
            doc.add(new Paragraph(nvl(text(index.path("name"))) + ": " + nvl(text(index.path("close"))) + " on "
                    + nvl(text(index.path("date"))) + ", " + nvl(text(index.path("vsSma200Pct")))
                    + "% against its 200-day average, 1Y " + nvl(text(index.path("change1yPct"))) + "%", BODY));
        }
        JsonNode presets = context.path("presets");
        if (presets.isArray() && !presets.isEmpty()) {
            PdfPTable table = new PdfPTable(new float[] {18, 10, 72});
            table.setWidthPercentage(100);
            table.setSpacingBefore(4);
            table.setHeaderRows(1);
            for (String h : List.of("Preset", "Stance", "Reading")) {
                table.addCell(headerCell(h));
            }
            for (JsonNode p : presets) {
                table.addCell(cell(p.path("name").asString(""), SMALL_BOLD, Element.ALIGN_LEFT));
                table.addCell(cell(p.path("stance").asString(""), SMALL, Element.ALIGN_CENTER));
                table.addCell(cell(p.path("stanceLabel").asString(""), SMALL, Element.ALIGN_LEFT));
            }
            doc.add(table);
        }
    }

    private void markets(Document doc, JsonNode context) {
        JsonNode markets = context == null ? null : context.path("markets");
        if (markets == null || markets.isMissingNode() || markets.isNull()) {
            return;
        }
        heading(doc, "Commodities and currencies");
        if (text(markets.path("termsOfTrade").path("summary")) != null) {
            doc.add(new Paragraph(text(markets.path("termsOfTrade").path("summary")), BODY));
        }
        List<JsonNode> rows = new ArrayList<>();
        markets.path("commodities").forEach(rows::add);
        markets.path("currencyPairs").forEach(rows::add);
        if (rows.isEmpty()) {
            return;
        }
        PdfPTable table = new PdfPTable(new float[] {20, 12, 9, 9, 9, 11, 30});
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setHeaderRows(1);
        for (String h : List.of("Instrument", "Last", "1M", "3M", "1Y", "For Indonesia", "Why")) {
            table.addCell(headerCell(h));
        }
        for (JsonNode r : rows) {
            table.addCell(cell(r.path("name").asString("") + " (" + r.path("lastDate").asString("") + ")", SMALL,
                    Element.ALIGN_LEFT));
            table.addCell(cell(String.format(Locale.ROOT, "%,.2f", r.path("last").asDouble()), SMALL, Element.ALIGN_RIGHT));
            table.addCell(cell(pct(r.path("change1mPct")), SMALL, Element.ALIGN_RIGHT));
            table.addCell(cell(pct(r.path("change3mPct")), SMALL, Element.ALIGN_RIGHT));
            table.addCell(cell(pct(r.path("change1yPct")), SMALL, Element.ALIGN_RIGHT));
            String tone = r.path("tone").asString("NEUTRAL");
            table.addCell(cell("POSITIVE".equals(tone) ? "supportive" : "NEGATIVE".equals(tone) ? "headwind" : "neutral",
                    new Font(Font.HELVETICA, 7.5f, Font.BOLD, "POSITIVE".equals(tone) ? GOOD : "NEGATIVE".equals(tone) ? BAD
                            : MUTED), Element.ALIGN_CENTER));
            table.addCell(cell(nvl(text(r.path("relevance"))), SMALL_MUTED, Element.ALIGN_LEFT));
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

    /** As the verdict bands: 60+ investable (green), 40 - 59 selective (amber), below 40 not (red). */
    private static Color color(Double score) {
        if (score == null) {
            return MUTED;
        }
        return score >= 60 ? GOOD : score >= 40 ? MID : BAD;
    }

    private static String verdictLabel(String verdict) {
        if (verdict == null) {
            return "-";
        }
        return switch (verdict) {
            case "INVESTABLE" -> "Investable";
            case "SELECTIVE" -> "Selective";
            case "NOT_INVESTABLE" -> "Not investable now";
            default -> verdict;
        };
    }

    private static String pct(JsonNode v) {
        return v == null || !v.isNumber() ? "-" : String.format(Locale.ROOT, "%+.1f%%", v.asDouble());
    }

    private static void appendList(StringBuilder b, String label, JsonNode list) {
        if (list == null || !list.isArray() || list.isEmpty()) {
            return;
        }
        List<String> items = new ArrayList<>();
        list.forEach(i -> items.add(i.asString("")));
        b.append('\n').append(label).append(": ").append(String.join("; ", items));
    }

    private static String reflection(MacroAgentView s) {
        if ("PRIOR_ONLY".equals(s.status()) || "NO_SCORE".equals(s.status())) {
            String what = "PRIOR_ONLY".equals(s.status()) ? "Rules prior only" : "No score";
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
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isNumber() || node.isBoolean() ? node.asString() : node.asString(null);
    }

    private static String nvl(Object value) {
        return value == null ? "-" : value.toString();
    }

    private static String dateTime(java.time.Instant instant) {
        return instant == null ? "-" : DATE_TIME.format(instant.atZone(JAKARTA)) + " WIB";
    }

    /** "Neraca Lab macro analysis report - page n" at the bottom of every page. */
    private static final class Footer extends PdfPageEventHelper {

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Rectangle page = document.getPageSize();
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("Neraca Lab macro analysis report - page " + writer.getPageNumber(), SMALL_MUTED),
                    page.getRight() - 36, page.getBottom() + 20, 0);
        }
    }
}
