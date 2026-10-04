package com.neracalab.backend.screening.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.neracalab.backend.screening.news.Headline;
import com.neracalab.backend.screening.news.NewsParsers.ArticleText;
import com.neracalab.backend.screening.news.NewsService;

/**
 * Tools of the research agent for one stock (Tool Calling Pattern). {@code readArticle} only reads
 * URLs of the stock's headline list (plus results of its own search), so the model cannot make the
 * server fetch arbitrary addresses.
 */
public class ResearchTools {

    static final int MAX_READS = 2;
    static final int MAX_SEARCHES = 1;

    private final NewsService news;
    private final List<Headline> known;
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger searches = new AtomicInteger();

    public ResearchTools(NewsService news, List<Headline> headlines) {
        this.news = news;
        this.known = new ArrayList<>(headlines);
    }

    public record ArticleView(String url, String title, String published, String lead, String text) {
    }

    @Tool(description = """
            Reads one news article from the headline list and returns its title, date, lead and the first \
            paragraphs. Use it for headlines that suggest material company news. At most 2 reads per stock.""")
    public ArticleView readArticle(@ToolParam(description = "URL exactly as given in the headline list") String url) {
        if (reads.incrementAndGet() > MAX_READS) {
            throw new IllegalStateException("Read limit reached (" + MAX_READS + " articles); answer with the brief");
        }
        String cleaned = url == null ? "" : url.trim();
        ArticleText text = news.read(cleaned, known);
        return new ArticleView(cleaned, text.title(), text.publishedAt() == null ? null : text.publishedAt().toString(),
                text.description(), text.body());
    }

    public record SearchResult(String url, String title, String published, String snippet) {
    }

    @Tool(description = """
            Searches recent Indonesian business news (Tavily) when the headline list is empty or says nothing \
            about the company. Returns titles, dates, snippets and URLs (readable with readArticle). At most once.""")
    public List<SearchResult> searchNews(@ToolParam(description = "query, e.g. 'BBCA Bank Central Asia laba 2026'")
                                         String query) {
        if (searches.incrementAndGet() > MAX_SEARCHES) {
            throw new IllegalStateException("Search limit reached; answer with the brief");
        }
        List<Headline> found = news.search(query == null ? "" : query.trim());
        known.addAll(found);
        return found.stream().map(h -> new SearchResult(h.url(), h.title(), date(h.publishedAt()), h.description()))
                .toList();
    }

    int reads() {
        return reads.get();
    }

    int searches() {
        return searches.get();
    }

    private static String date(Instant instant) {
        return instant == null ? null : instant.toString().substring(0, 10);
    }
}
