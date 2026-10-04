package com.neracalab.backend.screening.news;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * HTML of the crawled news sites -> headlines and article text. Every parser is defensive: a
 * changed layout yields fewer (or no) headlines, never an exception.
 * <ul>
 *   <li>EmitenNews tag page {@code /tag/<ticker>}: {@code a.search-result-item} cards</li>
 *   <li>IDX Channel tag page {@code /tag/<ticker>}: {@code div.bt-con} items</li>
 *   <li>Investor.id tag page {@code /tag/<ticker>}: rows of the {@code main} list</li>
 *   <li>Pasardana news listings: links {@code /news/yyyy/m/d/<slug>} (the date is in the path)</li>
 * </ul>
 */
public final class NewsParsers {

    static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private static final Pattern DMY_TIME = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4}),?\\s+(\\d{1,2}):(\\d{2})");
    private static final Pattern D_MON_Y_TIME = Pattern.compile(
            "(\\d{1,2})\\s+([A-Za-z]{3,9})\\s+(\\d{4})(?:\\s*[|,]?\\s*(\\d{1,2})[:.](\\d{2}))?");
    private static final Pattern PASARDANA_PATH = Pattern.compile("^/news/(\\d{4})/(\\d{1,2})/(\\d{1,2})/[a-z0-9-]+$");
    private static final Pattern INVESTOR_ARTICLE = Pattern.compile("^https://investor\\.id/[a-z-]+/\\d+/[a-z0-9-]+$");
    private static final Pattern DATE_PUBLISHED = Pattern.compile("\"datePublished\"\\s*:\\s*\"([^\"]+)\"");

    private static final Map<String, Integer> MONTHS = new LinkedHashMap<>();

    static {
        String[][] names = {{"jan", "januari", "january"}, {"feb", "februari", "february"}, {"mar", "maret", "march"},
                {"apr", "april"}, {"mei", "may"}, {"jun", "juni", "june"}, {"jul", "juli", "july"},
                {"agu", "agt", "ags", "agustus", "aug", "august"}, {"sep", "sept", "september"},
                {"okt", "oktober", "oct", "october"}, {"nov", "november", "nop", "nopember"},
                {"des", "desember", "dec", "december"}};
        for (int i = 0; i < names.length; i++) {
            for (String name : names[i]) {
                MONTHS.put(name, i + 1);
            }
        }
    }

    private NewsParsers() {
    }

    // ------------------------------------------------------------------ listings

    public static List<Headline> emitenNews(String html) {
        Document doc = Jsoup.parse(html, "https://www.emitennews.com/");
        Map<String, Headline> out = new LinkedHashMap<>();
        for (Element card : doc.select("a.search-result-item[href]")) {
            String url = card.absUrl("href");
            String title = text(card.selectFirst("h4"));
            if (!url.startsWith("https://www.emitennews.com/news/") || title == null) {
                continue;
            }
            out.putIfAbsent(url, new Headline(url, NewsSource.EMITENNEWS, title, text(card.selectFirst("p")),
                    dmyTime(text(card.selectFirst("span.small")))));
        }
        return List.copyOf(out.values());
    }

    public static List<Headline> idxChannel(String html) {
        Document doc = Jsoup.parse(html, "https://www.idxchannel.com/");
        Map<String, Headline> out = new LinkedHashMap<>();
        for (Element item : doc.select("div.bt-con")) {
            Element link = item.selectFirst("h2.list-berita-baru a[href]");
            if (link == null) {
                continue;
            }
            String url = link.absUrl("href");
            String title = text(link);
            if (!url.startsWith("https://www.idxchannel.com/") || title == null) {
                continue;
            }
            out.putIfAbsent(url, new Headline(url, NewsSource.IDXCHANNEL, title, null,
                    dmyTime(text(item.selectFirst("span.mh-clock")))));
        }
        return List.copyOf(out.values());
    }

    public static List<Headline> investorId(String html) {
        Document doc = Jsoup.parse(html, "https://investor.id/");
        Element main = doc.selectFirst("main");
        if (main == null) {
            return List.of();
        }
        Map<String, Headline> out = new LinkedHashMap<>();
        for (Element row : main.select("div.row.mb-4")) {
            Element title = row.selectFirst("h4");
            String url = null;
            for (Element a : row.select("a[href]")) {
                String href = a.absUrl("href");
                if (INVESTOR_ARTICLE.matcher(href).matches()) {
                    url = href;
                    break;
                }
            }
            if (url == null || text(title) == null) {
                continue;
            }
            out.putIfAbsent(url, new Headline(url, NewsSource.INVESTOR_ID, text(title),
                    text(row.selectFirst("span.text-truncate-2-lines")),
                    dayMonthYear(text(row.selectFirst("span.text-muted.small")))));
        }
        return List.copyOf(out.values());
    }

    /** Pasardana listing pages (home page, /news, /market-analysis): every news link with its title. */
    public static List<Headline> pasardana(String html) {
        Document doc = Jsoup.parse(html, "https://pasardana.id/");
        Map<String, Headline> out = new LinkedHashMap<>();
        for (Element a : doc.select("a[href^=/news/]")) {
            String path = a.attr("href");
            Matcher m = PASARDANA_PATH.matcher(path);
            if (!m.matches()) {
                continue;
            }
            Element strong = a.selectFirst("p.font-semibold, h2, h3, h4");
            String title = strong != null ? text(strong) : text(a);
            if (title == null || title.length() < 15) {
                Element img = a.selectFirst("img[alt]");
                title = img == null ? title : img.attr("alt").trim();
            }
            if (title == null || title.length() < 15) {
                continue;
            }
            Instant date;
            try {
                date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3))).atStartOfDay(WIB).toInstant();
            } catch (RuntimeException e) {
                date = null;
            }
            String url = "https://pasardana.id" + path;
            out.putIfAbsent(url, new Headline(url, NewsSource.PASARDANA, title, null, date));
        }
        return List.copyOf(out.values());
    }

    // ------------------------------------------------------------------ article

    /** Title, lead, date and the first paragraphs of an article page. */
    public record ArticleText(String title, String description, Instant publishedAt, String body) {
    }

    public static ArticleText article(String html, String url, int maxChars) {
        Document doc = Jsoup.parse(html, url);
        doc.select("script:not([type=application/ld+json]), style, nav, footer, aside, form, noscript, iframe").remove();
        String title = meta(doc, "og:title");
        if (title == null) {
            title = text(doc.selectFirst("h1"));
        }
        String description = meta(doc, "og:description");
        Instant published = isoInstant(meta(doc, "article:published_time"));
        if (published == null) {
            Matcher m = DATE_PUBLISHED.matcher(html);
            if (m.find()) {
                published = isoInstant(m.group(1));
            }
        }
        Element root = doc.selectFirst("article");
        if (root == null) {
            root = doc.body();
        }
        StringBuilder body = new StringBuilder();
        if (root != null) {
            for (Element p : root.select("p")) {
                String t = text(p);
                if (t == null || t.length() < 40 || t.startsWith("Baca juga") || t.startsWith("BACA JUGA")) {
                    continue;
                }
                if (body.length() + t.length() + 1 > maxChars) {
                    int room = maxChars - body.length();
                    if (room > 80) {
                        body.append(t, 0, room).append('…');
                    }
                    break;
                }
                body.append(t).append('\n');
            }
        }
        return new ArticleText(title, description, published, body.toString().trim());
    }

    // ------------------------------------------------------------------ helpers

    /** "28/09/2026, 21:01 WIB" / "02/10/2026 18:16 WIB" (Jakarta time). */
    static Instant dmyTime(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = DMY_TIME.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDateTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)))
                    .atZone(WIB).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "3 Okt 2026 | 10:00 WIB", "03 Oktober 2026, 10:40" (Indonesian or English month names, Jakarta time). */
    static Instant dayMonthYear(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = D_MON_Y_TIME.matcher(text);
        if (!m.find()) {
            return null;
        }
        Integer month = MONTHS.get(m.group(2).toLowerCase(Locale.ROOT));
        if (month == null) {
            return null;
        }
        try {
            int hour = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            int minute = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
            return LocalDateTime.of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(1)), hour, minute)
                    .atZone(WIB).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Instant isoInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text.trim()).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(text.trim());
            } catch (DateTimeParseException f) {
                try {
                    return LocalDateTime.parse(text.trim()).atZone(WIB).toInstant();
                } catch (DateTimeParseException g) {
                    return null;
                }
            }
        }
    }

    private static String meta(Document doc, String property) {
        Element e = doc.selectFirst("meta[property=" + property + "], meta[name=" + property + "]");
        if (e == null) {
            return null;
        }
        String content = e.attr("content").trim();
        return content.isEmpty() ? null : content;
    }

    /** Whitespace-normalized text of an element; null when empty. */
    static String text(Element e) {
        if (e == null) {
            return null;
        }
        String t = e.text().replace(' ', ' ').replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    /** Keeps the list order but drops later duplicates of an URL. */
    static List<Headline> distinct(List<Headline> headlines) {
        Map<String, Headline> out = new LinkedHashMap<>();
        for (Headline h : headlines) {
            out.putIfAbsent(h.url(), h);
        }
        return new ArrayList<>(out.values());
    }
}
