package com.neracalab.backend.screening.news;

import java.time.Instant;

/**
 * One news item: what the research agent sees before deciding to read an article.
 *
 * @param description lead / snippet (may be null)
 * @param publishedAt null when the source shows no date
 */
public record Headline(String url, NewsSource source, String title, String description, Instant publishedAt) {
}
