package com.neracalab.backend.screening.news;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.screening.news.NewsParsers.ArticleText;

/**
 * News cache of the research agent: {@code news_article} (one row per URL), the tickers an
 * article was found for, when each source was last asked about a ticker, and the daily news brief
 * per ticker.
 */
@Repository
public class NewsRepository {

    private final JdbcClient jdbc;

    public NewsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores headlines found for a ticker (new URLs inserted, known ones completed) and links them to it. */
    public void saveHeadlines(String exchange, String ticker, List<Headline> headlines) {
        for (Headline h : headlines) {
            long id = jdbc.sql("""
                            INSERT INTO news_article (url, source, title, description, published_at)
                            VALUES (:url, :source, :title, :description, :published)
                            ON CONFLICT (url) DO UPDATE SET
                                title = EXCLUDED.title,
                                description = COALESCE(EXCLUDED.description, news_article.description),
                                published_at = COALESCE(news_article.published_at, EXCLUDED.published_at)
                            RETURNING article_id""")
                    .param("url", truncate(h.url(), 1000))
                    .param("source", h.source().name())
                    .param("title", truncate(h.title(), 500))
                    .param("description", h.description(), Types.VARCHAR)
                    .param("published", timestamp(h.publishedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .query(Long.class).single();
            jdbc.sql("""
                            INSERT INTO news_article_ticker (article_id, exchange, ticker) VALUES (:id, :exchange, :ticker)
                            ON CONFLICT DO NOTHING""")
                    .param("id", id).param("exchange", exchange).param("ticker", ticker)
                    .update();
        }
    }

    public void markFetched(String exchange, String ticker, NewsSource source, int count, String error) {
        jdbc.sql("""
                        INSERT INTO news_source_fetch (exchange, ticker, source, fetched_at, article_count, error)
                        VALUES (:exchange, :ticker, :source, now(), :count, :error)
                        ON CONFLICT (exchange, ticker, source) DO UPDATE SET
                            fetched_at = now(), article_count = EXCLUDED.article_count, error = EXCLUDED.error""")
                .param("exchange", exchange).param("ticker", ticker).param("source", source.name())
                .param("count", count).param("error", truncate(error, 500), Types.VARCHAR)
                .update();
    }

    /** When the source was last asked about the ticker successfully. */
    public Optional<Instant> lastFetched(String exchange, String ticker, NewsSource source) {
        return jdbc.sql("""
                        SELECT fetched_at FROM news_source_fetch
                        WHERE exchange = :exchange AND ticker = :ticker AND source = :source AND error IS NULL""")
                .param("exchange", exchange).param("ticker", ticker).param("source", source.name())
                .query((rs, i) -> instant(rs, "fetched_at"))
                .optional();
    }

    /** Headlines of a ticker published (or, without date, found) since {@code since}, newest first. */
    public List<Headline> headlines(String exchange, String ticker, Instant since, int limit) {
        return jdbc.sql("""
                        SELECT a.url, a.source, a.title, a.description, a.published_at
                        FROM news_article a
                        JOIN news_article_ticker t ON t.article_id = a.article_id
                        WHERE t.exchange = :exchange AND t.ticker = :ticker
                          AND COALESCE(a.published_at, a.fetched_at) >= :since
                        ORDER BY COALESCE(a.published_at, a.fetched_at) DESC, a.article_id DESC
                        LIMIT :limit""")
                .param("exchange", exchange).param("ticker", ticker)
                .param("since", timestamp(since), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit)
                .query((rs, i) -> new Headline(rs.getString("url"), NewsSource.valueOf(rs.getString("source")),
                        rs.getString("title"), rs.getString("description"), instant(rs, "published_at")))
                .list();
    }

    /** The stored text of an article that was read before. */
    public Optional<ArticleText> article(String url) {
        return jdbc.sql("""
                        SELECT title, description, published_at, body_excerpt FROM news_article
                        WHERE url = :url AND body_fetched_at IS NOT NULL""")
                .param("url", url)
                .query((rs, i) -> new ArticleText(rs.getString("title"), rs.getString("description"),
                        instant(rs, "published_at"), rs.getString("body_excerpt")))
                .optional();
    }

    public void saveArticle(String url, NewsSource source, ArticleText text) {
        jdbc.sql("""
                        INSERT INTO news_article (url, source, title, description, body_excerpt, published_at, body_fetched_at)
                        VALUES (:url, :source, :title, :description, :body, :published, now())
                        ON CONFLICT (url) DO UPDATE SET
                            description = COALESCE(EXCLUDED.description, news_article.description),
                            body_excerpt = EXCLUDED.body_excerpt,
                            published_at = COALESCE(news_article.published_at, EXCLUDED.published_at),
                            body_fetched_at = now()""")
                .param("url", truncate(url, 1000))
                .param("source", source.name())
                .param("title", truncate(text.title() == null ? url : text.title(), 500))
                .param("description", text.description(), Types.VARCHAR)
                .param("body", text.body(), Types.VARCHAR)
                .param("published", timestamp(text.publishedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    // ------------------------------------------------------------------ brief

    /** The research brief of a ticker of a day (JSON), if one was made. */
    public Optional<String> brief(String exchange, String ticker, LocalDate date) {
        return jdbc.sql("SELECT brief::text FROM news_brief WHERE exchange = :e AND ticker = :t AND brief_date = :d")
                .param("e", exchange).param("t", ticker).param("d", date)
                .query(String.class)
                .optional();
    }

    public void saveBrief(String exchange, String ticker, LocalDate date, String briefJson, String model) {
        jdbc.sql("""
                        INSERT INTO news_brief (exchange, ticker, brief_date, brief, model)
                        VALUES (:e, :t, :d, CAST(:brief AS jsonb), :model)
                        ON CONFLICT (exchange, ticker, brief_date) DO UPDATE SET
                            brief = EXCLUDED.brief, model = EXCLUDED.model, created_at = now()""")
                .param("e", exchange).param("t", ticker).param("d", date)
                .param("brief", briefJson).param("model", model, Types.VARCHAR)
                .update();
    }

    // ------------------------------------------------------------------ helpers

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
