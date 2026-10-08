package com.neracalab.backend.screening.news;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.news.NewsHttpClient.NewsFetchException;
import com.neracalab.backend.screening.news.NewsParsers.ArticleText;
import com.neracalab.backend.screening.news.TavilyClient.TavilyException;

/**
 * News of a listing from the four crawled sites and Tavily, cached in the database:
 * <ul>
 *   <li>EmitenNews, IDX Channel, Investor.id: the ticker's tag page ({@code /tag/<ticker>})</li>
 *   <li>Pasardana: the latest news listings, matched by ticker / company name (the site renders its
 *       search in the browser, so only the server-rendered listings are crawled)</li>
 *   <li>Tavily: one news search per ticker</li>
 * </ul>
 * A source is asked about a ticker again only after {@code news.cache-ttl}; articles older than
 * {@code news.max-age} are ignored.
 */
@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);
    private static final List<String> PASARDANA_LISTINGS = List.of("https://pasardana.id/",
            "https://pasardana.id/news", "https://pasardana.id/market-analysis");

    private final NewsHttpClient http;
    private final TavilyClient tavily;
    private final NewsRepository repository;
    private final ScreeningProperties.News properties;

    private volatile List<Headline> pasardanaLatest = List.of();
    private volatile Instant pasardanaFetchedAt = Instant.MIN;

    public NewsService(NewsHttpClient http, TavilyClient tavily, NewsRepository repository, ScreeningProperties properties) {
        this.http = http;
        this.tavily = tavily;
        this.repository = repository;
        this.properties = properties.news();
    }

    /** What happened per source while gathering (for the report). */
    public record SourceResult(NewsSource source, int found, boolean cached, String error) {
    }

    public record Gathered(List<Headline> headlines, List<SourceResult> sources) {
    }

    /**
     * Gathers the news of a listing from every source (cached sources are not asked again) and
     * returns the newest headlines.
     */
    public Gathered gather(String exchange, String ticker, String companyName) {
        List<SourceResult> sources = new ArrayList<>();
        for (NewsSource source : List.of(NewsSource.EMITENNEWS, NewsSource.IDXCHANNEL, NewsSource.INVESTOR_ID,
                NewsSource.PASARDANA, NewsSource.TAVILY)) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (source == NewsSource.TAVILY && !tavily.enabled()) {
                continue;
            }
            if (fresh(exchange, ticker, source)) {
                sources.add(new SourceResult(source, 0, true, null));
                continue;
            }
            try {
                List<Headline> found = recent(fetch(source, ticker, companyName));
                repository.saveHeadlines(exchange, ticker, found);
                repository.markFetched(exchange, ticker, source, found.size(), null);
                sources.add(new SourceResult(source, found.size(), false, null));
            } catch (NewsFetchException | TavilyException e) {
                log.info("news of {} from {}: {}", ticker, source, e.getMessage());
                repository.markFetched(exchange, ticker, source, 0, e.getMessage());
                sources.add(new SourceResult(source, 0, false, e.getMessage()));
            }
        }
        return new Gathered(headlines(exchange, ticker), sources);
    }

    /** The stored headlines of a listing within the maximum age, newest first. */
    public List<Headline> headlines(String exchange, String ticker) {
        return repository.headlines(exchange, ticker, Instant.now().minus(properties.maxAge()),
                properties.headlinesPerStock());
    }

    private boolean fresh(String exchange, String ticker, NewsSource source) {
        Optional<Instant> last = repository.lastFetched(exchange, ticker, source);
        return last.isPresent() && last.get().isAfter(Instant.now().minus(properties.cacheTtl()));
    }

    private List<Headline> fetch(NewsSource source, String ticker, String companyName) {
        String tag = ticker.toLowerCase(Locale.ROOT);
        return switch (source) {
            case EMITENNEWS -> NewsParsers.emitenNews(page("https://www.emitennews.com/tag/" + tag));
            case IDXCHANNEL -> NewsParsers.idxChannel(page("https://www.idxchannel.com/tag/" + tag));
            case INVESTOR_ID -> NewsParsers.investorId(page("https://investor.id/tag/" + tag));
            case PASARDANA -> pasardanaLatest().stream().filter(h -> mentions(h, ticker, companyName)).toList();
            case TAVILY -> tavily.search(ticker + " " + shortName(companyName) + " saham",
                    (int) Math.min(90, properties.maxAge().toDays()));
        };
    }

    /** The latest Pasardana listings, fetched at most once per cache window for all tickers. */
    private synchronized List<Headline> pasardanaLatest() {
        if (pasardanaFetchedAt.isAfter(Instant.now().minus(properties.cacheTtl()))) {
            return pasardanaLatest;
        }
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
        pasardanaLatest = NewsParsers.distinct(all);
        pasardanaFetchedAt = Instant.now();
        return pasardanaLatest;
    }

    private String page(String url) {
        NewsHttpClient.Page page = http.get(URI.create(url));
        if (page.status() == 404) {
            return "";   // no tag page for this ticker: no news there
        }
        if (page.status() != 200 || page.body() == null) {
            throw new NewsFetchException(URI.create(url).getHost() + " answered HTTP " + page.status(), null);
        }
        return page.body();
    }

    private List<Headline> recent(List<Headline> headlines) {
        Instant oldest = Instant.now().minus(properties.maxAge());
        return headlines.stream().filter(h -> h.publishedAt() == null || h.publishedAt().isAfter(oldest)).toList();
    }

    /** A headline is about a listing when it names the ticker (as a word) or the company's short name. */
    public static boolean mentions(Headline h, String ticker, String companyName) {
        String text = h.title() + " " + h.url().replace('-', ' ');
        if (Pattern.compile("\\b" + Pattern.quote(ticker) + "\\b").matcher(h.title()).find()
                || Pattern.compile("\\b" + Pattern.quote(ticker.toLowerCase(Locale.ROOT)) + "\\b").matcher(h.url().replace('-', ' ')).find()) {
            return true;
        }
        String name = shortName(companyName);
        return name.length() >= 6 && text.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT));
    }

    /** "PT Bank Central Asia Tbk." -> "Bank Central Asia" */
    public static String shortName(String companyName) {
        if (companyName == null) {
            return "";
        }
        return companyName.replaceAll("(?i)^PT\\.?\\s+", "").replaceAll("(?i),?\\s+Tbk\\.?$", "")
                .replaceAll("(?i)\\s+\\(Persero\\)", "").trim();
    }

    // ------------------------------------------------------------------ tools of the research agent

    /**
     * Reads an article (cached after the first read). Only URLs of the crawled sites or URLs the
     * gathering found (Tavily results) are fetched.
     *
     * @throws IllegalArgumentException for an URL that is not allowed
     */
    public ArticleText read(String url, List<Headline> known) {
        Optional<ArticleText> cached = repository.article(url);
        if (cached.isPresent()) {
            return cached.get();
        }
        Headline headline = known.stream().filter(h -> h.url().equals(url)).findFirst().orElse(null);
        NewsSource site = NewsSource.ofUrl(url);
        if (headline == null && site == null) {
            throw new IllegalArgumentException("Only the listed article URLs can be read");
        }
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Not a web URL");
        }
        NewsHttpClient.Page page = http.get(uri);
        if (page.status() != 200 || page.body() == null) {
            throw new NewsFetchException(uri.getHost() + " answered HTTP " + page.status(), null);
        }
        ArticleText text = NewsParsers.article(page.body(), url, properties.articleChars());
        NewsSource source = site != null ? site : headline.source();
        repository.saveArticle(url, source, text);
        return text;
    }

    /** Free news search (Tavily) for the research agent. */
    public List<Headline> search(String query) {
        return recent(tavily.search(query, (int) Math.min(90, properties.maxAge().toDays())));
    }

    public boolean searchEnabled() {
        return tavily.enabled();
    }
}
