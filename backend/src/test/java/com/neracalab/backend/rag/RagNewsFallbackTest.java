package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.neracalab.backend.rag.RagRepository.Company;
import com.neracalab.backend.rag.RagRepository.NewDocument;
import com.neracalab.backend.screening.news.Headline;
import com.neracalab.backend.screening.news.NewsHttpClient;
import com.neracalab.backend.screening.news.NewsSource;

/** A news site refusing our request (HTTP 403): the article is stored from the search engine's page text. */
class RagNewsFallbackTest {

    private static final String URL = "https://investasi.kontan.co.id/news/astra-graphia-asgr-tebar-dividen-di-atas-laba";
    private static final Company ASGR = new Company(7, "IDX", "ASGR", "PT Astra Graphia Tbk");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 8);
    private static final Headline HEADLINE = new Headline(URL, NewsSource.TAVILY, "ASGR Tebar Dividen", "lead",
            Instant.parse("2026-04-20T03:00:00Z"));

    private RagRepository repository;
    private EmbeddingClient embeddings;
    private NewsCollector collector;
    private NewsHttpClient http;
    private RagIngestionService service;

    @BeforeEach
    void setUp() {
        repository = mock(RagRepository.class);
        embeddings = mock(EmbeddingClient.class);
        collector = mock(NewsCollector.class);
        http = mock(NewsHttpClient.class);
        when(embeddings.model()).thenReturn("stub");
        when(embeddings.embed(anyList())).thenAnswer(a -> ((List<?>) a.getArgument(0)).stream()
                .map(t -> new float[RagProperties.STORE_DIMENSIONS]).toList());
        when(http.get(URI.create(URL))).thenReturn(new NewsHttpClient.Page(403, "Forbidden"));
        service = new RagIngestionService(repository, embeddings, collector, http, EmbeddingClientTest.properties());
    }

    private void collected(Map<String, String> searchText) {
        when(collector.collect(eq("ASGR"), anyString(), eq(FROM), eq(TO), any())).thenReturn(new NewsCollector.Collected(
                List.of(HEADLINE), List.of(new NewsCollector.SourceResult(NewsSource.TAVILY, 1, 1, 1, null)), searchText));
    }

    @Test
    void storesTheSearchTextWhenTheSiteRefuses() {
        collected(Map.of(URL, "Astra Graphia membagikan dividen. ".repeat(40)));
        RagIngestionService.NewsResult result = service.ingestNews(UUID.randomUUID(), ASGR, FROM, TO, s -> { });

        assertThat(result.stored()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.articles()).singleElement()
                .satisfies(a -> assertThat(a.outcome()).isEqualTo("STORED_FROM_SEARCH_TEXT"));
        verify(repository).store(any(NewDocument.class), anyList(), anyList());
    }

    @Test
    void failsTheArticleWithoutUsableSearchText() {
        collected(Map.of(URL, "too short"));
        RagIngestionService.NewsResult result = service.ingestNews(UUID.randomUUID(), ASGR, FROM, TO, s -> { });

        assertThat(result.stored()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.articles().getFirst().outcome()).startsWith("FAILED: ").contains("HTTP 403");
        verify(repository, never()).store(any(), anyList(), anyList());
        verify(repository).hasDocument(anyLong(), eq(RagRepository.SourceType.NEWS), eq(URL));
    }
}
