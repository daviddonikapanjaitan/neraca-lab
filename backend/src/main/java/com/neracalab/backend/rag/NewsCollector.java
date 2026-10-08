package com.neracalab.backend.rag;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.neracalab.backend.screening.news.Headline;
import com.neracalab.backend.screening.news.NewsHttpClient;
import com.neracalab.backend.screening.news.NewsHttpClient.NewsFetchException;
import com.neracalab.backend.screening.news.NewsParsers;
import com.neracalab.backend.screening.news.NewsService;
import com.neracalab.backend.screening.news.NewsSource;
import com.neracalab.backend.screening.news.TavilyClient;
import com.neracalab.backend.screening.news.TavilyClient.TavilyException;

/**
 * News headlines of a company within a date range, from the sites the screening crawls (same parsers and polite
 * HTTP client) and the Tavily search:
 * <ul>
 *   <li>EmitenNews {@code /tag/<ticker>}, then older pages {@code /tag/<ticker>/<9 n>}, and Investor.id
 *       {@code /tag/<ticker>}, then {@code /tag/<ticker>/<n>}: walked back until a page ends before the range start
 *       (at most {@code news-max-pages} pages per site);</li>
 *   <li>IDX Channel {@code /tag/<ticker>}: the latest articles only (older pages are loaded by script);</li>
 *   <li>Pasardana: the latest listings, kept when they name the ticker or the company;</li>
 *   <li>Tavily: a news search with the range's start and end date (when configured), without social media;
 *       only results that look like articles are kept ({@link NewsUrls}: no quote / profile / listing pages), with
 *       the page text Tavily read, used when the site refuses our own request.</li>
 * </ul>
 * Headlines are kept when published within the range (Jakarta time); an undated one is kept for now - its date is
 * taken from the article when it is read.
 */
@Component
public class NewsCollector {

    static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    /** EmitenNews lists 9 articles per tag page; older pages are /tag/<ticker>/9, /18, ... */
    static final int EMITENNEWS_PAGE_SIZE = 9;
    private static final List<String> PASARDANA_LISTINGS = List.of("https://pasardana.id/",
            "https://pasardana.id/news", "https://pasardana.id/market-analysis");

    /**
     * What a source contributed.
     *
     * @param notArticles results left out because they are no news article (quote / profile / listing pages)
     */
    public record SourceResult(NewsSource source, int pages, int found, int inRange, int notArticles, String error) {

        public SourceResult(NewsSource source, int pages, int found, int inRange, String error) {
            this(source, pages, found, inRange, 0, error);
        }
    }

    /** @param searchText page text of search results by URL (Tavily), used when the page itself cannot be read */
    public record Collected(List<Headline> headlines, List<SourceResult> sources, Map<String, String> searchText) {
    }

    private final NewsHttpClient http;
    private final TavilyClient tavily;
    private final RagProperties properties;

    public NewsCollector(NewsHttpClient http, TavilyClient tavily, RagProperties properties) {
        this.http = http;
        this.tavily = tavily;
        this.properties = properties;
    }

    /** Start (inclusive) and end (exclusive) instants of a date range in Jakarta time. */
    static Instant start(LocalDate from) {
        return from.atStartOfDay(WIB).toInstant();
    }

    static Instant end(LocalDate to) {
        return to.plusDays(1).atStartOfDay(WIB).toInstant();
    }

    static boolean inRange(Instant at, LocalDate from, LocalDate to) {
        return at == null || (!at.isBefore(start(from)) && at.isBefore(end(to)));
    }

    public Collected collect(String ticker, String companyName, LocalDate from, LocalDate to, Consumer<String> progress) {
        String tag = ticker.toLowerCase(Locale.ROOT);
        List<SourceResult> results = new ArrayList<>();
        List<Headline> all = new ArrayList<>();

        progress.accept("Reading EmitenNews (" + ticker + ")");
        walk(NewsSource.EMITENNEWS, n -> "https://www.emitennews.com/tag/" + tag + (n == 0 ? "" : "/" + n * EMITENNEWS_PAGE_SIZE),
                NewsParsers::emitenNews, from, to, all, results);
        progress.accept("Reading Investor.id (" + ticker + ")");
        walk(NewsSource.INVESTOR_ID, n -> "https://investor.id/tag/" + tag + (n == 0 ? "" : "/" + (n + 1)),
                NewsParsers::investorId, from, to, all, results);
        progress.accept("Reading IDX Channel (" + ticker + ")");
        single(NewsSource.IDXCHANNEL, () -> NewsParsers.idxChannel(page("https://www.idxchannel.com/tag/" + tag)),
                from, to, all, results);
        progress.accept("Reading Pasardana");
        single(NewsSource.PASARDANA, () -> pasardana().stream().filter(h -> NewsService.mentions(h, ticker, companyName)).toList(),
                from, to, all, results);
        Map<String, String> searchText = new LinkedHashMap<>();
        if (tavily.enabled()) {
            progress.accept("Searching the news (Tavily)");
            tavily(ticker, companyName, from, to, all, results, searchText);
        }

        Map<String, Headline> byUrl = new LinkedHashMap<>();
        for (Headline h : all) {
            byUrl.merge(h.url(), h, (a, b) -> a.publishedAt() != null ? a : b);
        }
        List<Headline> headlines = new ArrayList<>(byUrl.values());
        headlines.sort(Comparator.comparing(Headline::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return new Collected(List.copyOf(headlines), List.copyOf(results), Map.copyOf(searchText));
    }

    /** Pages of a tag listing, newest first, until a page reaches back before the range start (or is empty). */
    private void walk(NewsSource source, Function<Integer, String> pageUrl, Function<String, List<Headline>> parser,
                      LocalDate from, LocalDate to, List<Headline> into, List<SourceResult> results) {
        int pages = 0;
        int found = 0;
        int kept = 0;
        String error = null;
        try {
            for (int n = 0; n < properties.newsMaxPages() && !Thread.currentThread().isInterrupted(); n++) {
                List<Headline> page = parser.apply(page(pageUrl.apply(n)));
                pages++;
                if (page.isEmpty()) {
                    break;
                }
                found += page.size();
                for (Headline h : page) {
                    if (inRange(h.publishedAt(), from, to)) {
                        into.add(h);
                        kept++;
                    }
                }
                Instant oldest = page.stream().map(Headline::publishedAt).filter(java.util.Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null);
                if (oldest == null || oldest.isBefore(start(from))) {
                    break;
                }
            }
        } catch (NewsFetchException e) {
            error = e.getMessage();
        }
        results.add(new SourceResult(source, pages, found, kept, error));
    }

    private void single(NewsSource source, java.util.function.Supplier<List<Headline>> fetch, LocalDate from, LocalDate to,
                        List<Headline> into, List<SourceResult> results) {
        try {
            List<Headline> found = fetch.get();
            List<Headline> kept = found.stream().filter(h -> inRange(h.publishedAt(), from, to)).toList();
            into.addAll(kept);
            results.add(new SourceResult(source, 1, found.size(), kept.size(), null));
        } catch (NewsFetchException | TavilyException e) {
            results.add(new SourceResult(source, 0, 0, 0, e.getMessage()));
        }
    }

    private void tavily(String ticker, String companyName, LocalDate from, LocalDate to, List<Headline> into,
                        List<SourceResult> results, Map<String, String> searchText) {
        try {
            List<TavilyClient.Result> found = tavily.search(ticker + " " + NewsService.shortName(companyName) + " saham",
                    from, to, 20, NewsUrls.SOCIAL_DOMAINS);
            int notArticles = 0;
            int kept = 0;
            for (TavilyClient.Result r : found) {
                Headline h = r.headline();
                if (!NewsUrls.isArticle(h.url(), ticker)) {
                    notArticles++;
                } else if (inRange(h.publishedAt(), from, to)) {
                    into.add(h);
                    kept++;
                    if (r.rawContent() != null) {
                        searchText.put(h.url(), r.rawContent());
                    }
                }
            }
            results.add(new SourceResult(NewsSource.TAVILY, 1, found.size(), kept, notArticles, null));
        } catch (TavilyException e) {
            results.add(new SourceResult(NewsSource.TAVILY, 0, 0, 0, e.getMessage()));
        }
    }

    private List<Headline> pasardana() {
        List<Headline> all = new ArrayList<>();
        NewsFetchException failure = null;
        for (String url : PASARDANA_LISTINGS) {
            try {
                all.addAll(NewsParsers.pasardana(page(url)));
            } catch (NewsFetchException e) {
                failure = e;
            }
        }
        if (all.isEmpty() && failure != null) {
            throw failure;
        }
        return all;
    }

    /** A page's HTML; "" for HTTP 404 (no such tag page). */
    String page(String url) {
        NewsHttpClient.Page page = http.get(URI.create(url));
        if (page.status() == 404) {
            return "";
        }
        if (page.status() != 200 || page.body() == null) {
            throw new NewsFetchException(URI.create(url).getHost() + " answered HTTP " + page.status(), null);
        }
        return page.body();
    }
}
