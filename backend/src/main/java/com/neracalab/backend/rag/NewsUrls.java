package com.neracalab.backend.rag;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which search results are news articles. A web search (Tavily, even with {@code topic: news}) also returns stock
 * quote and company profile pages (Yahoo Finance, ajaib, cermati, idx.co.id), topic / tag listings and social media
 * posts; those carry a date (the crawl date) but no article, so they are left out before anything is read.
 */
public final class NewsUrls {

    /** Social media and video sites: posts, not articles (also sent to Tavily as {@code exclude_domains}). */
    public static final List<String> SOCIAL_DOMAINS = List.of("instagram.com", "facebook.com", "fb.com", "x.com",
            "twitter.com", "tiktok.com", "youtube.com", "youtu.be", "linkedin.com", "threads.net", "t.me",
            "pinterest.com", "reddit.com");

    /** Path parts of listings, quotes and profiles. */
    private static final Pattern NOT_ARTICLE_PATH = Pattern.compile(
            "/(tag|tags|topic|topik|category|kategori|search|quote|quotes|symbols?|stocks?|saham|emiten|"
                    + "perusahaan-tercatat|profil-perusahaan|company-profile|profile|markets?/stocks?)(/|$)",
            Pattern.CASE_INSENSITIVE);

    /** An article slug: four or more words joined by hyphens ("asgr-tebar-dividen-rp297-per-saham"). */
    private static final Pattern SLUG = Pattern.compile("^[\\p{L}\\p{N}]+(-[\\p{L}\\p{N}]+){3,}(\\.\\w+)?$");

    private NewsUrls() {
    }

    static boolean isSocial(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return SOCIAL_DOMAINS.stream().anyMatch(d -> h.equals(d) || h.endsWith("." + d));
    }

    /**
     * Whether a search result may be a news article of the company: an http(s) URL, not social media, not a
     * listing / quote / profile path (unless it has an article slug), and not a page named after the ticker
     * itself ({@code .../ASGR}, {@code .../ASGR.JK}).
     */
    public static boolean isArticle(String url, String ticker) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            return false;
        }
        if (isSocial(uri.getHost())) {
            return false;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.isEmpty() || path.equals("/")) {
            return false;
        }
        String[] segments = path.split("/");
        boolean slug = Arrays.stream(segments).anyMatch(seg -> SLUG.matcher(seg).matches());
        // a section such as /saham/ can hold articles: a long slug wins over the listing path
        if (!slug && NOT_ARTICLE_PATH.matcher(path).find()) {
            return false;
        }
        String last = segments.length == 0 ? "" : segments[segments.length - 1].toUpperCase(Locale.ROOT);
        String t = ticker.toUpperCase(Locale.ROOT);
        return !(last.equals(t) || last.equals(t + ".JK"));
    }
}
