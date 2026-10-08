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
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.neracalab.backend.rag.RagRepository.Company;
import com.neracalab.backend.rag.RagRepository.NewDocument;
import com.neracalab.backend.screening.news.Headline;
import com.neracalab.backend.screening.news.NewsHttpClient;
import com.neracalab.backend.screening.news.NewsSource;

/** News articles of a range: read from their site and stored; a site refusing our request fails that article only. */
class RagNewsIngestionTest {

    private static final String URL = "https://www.emitennews.com/news/asgr-jadwal-dividen-rp297-per-helai-yield-1338-persen";
    private static final Company ASGR = new Company(7, "IDX", "ASGR", "PT Astra Graphia Tbk");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 8);
    private static final Headline HEADLINE = new Headline(URL, NewsSource.EMITENNEWS, "ASGR Jadwal Dividen", "lead",
            Instant.parse("2026-04-20T03:00:00Z"));

    private RagRepository repository;
    private NewsHttpClient http;
    private RagIngestionService service;

    @BeforeEach
    void setUp() {
        repository = mock(RagRepository.class);
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        NewsCollector collector = mock(NewsCollector.class);
        http = mock(NewsHttpClient.class);
        when(embeddings.model()).thenReturn("stub");
        when(embeddings.embed(anyList())).thenAnswer(a -> ((List<?>) a.getArgument(0)).stream()
                .map(t -> new float[RagProperties.STORE_DIMENSIONS]).toList());
        when(collector.collect(eq("ASGR"), anyString(), eq(FROM), eq(TO), any())).thenReturn(new NewsCollector.Collected(
                List.of(HEADLINE), List.of(new NewsCollector.SourceResult(NewsSource.EMITENNEWS, 1, 1, 1, null))));
        service = new RagIngestionService(repository, embeddings, collector, http, EmbeddingClientTest.properties());
    }

    @Test
    void storesTheArticleReadFromItsSite() {
        String body = "<html><head><title>ASGR Jadwal Dividen</title></head><body><article>"
                + "<p>Astra Graphia membagikan dividen tunai Rp297 per helai kepada pemegang saham.</p>".repeat(10)
                + "</article></body></html>";
        when(http.get(URI.create(URL))).thenReturn(new NewsHttpClient.Page(200, body));
        RagIngestionService.NewsResult result = service.ingestNews(UUID.randomUUID(), ASGR, FROM, TO, s -> { });

        assertThat(result.stored()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.articles()).singleElement().satisfies(a -> assertThat(a.outcome()).isEqualTo("STORED"));
        verify(repository).store(any(NewDocument.class), anyList(), anyList());
    }

    @Test
    void failsTheArticleWhenTheSiteRefuses() {
        when(http.get(URI.create(URL))).thenReturn(new NewsHttpClient.Page(403, "Forbidden"));
        RagIngestionService.NewsResult result = service.ingestNews(UUID.randomUUID(), ASGR, FROM, TO, s -> { });

        assertThat(result.stored()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.articles().getFirst().outcome()).startsWith("FAILED: ").contains("HTTP 403");
        verify(repository, never()).store(any(), anyList(), anyList());
        verify(repository).hasDocument(anyLong(), eq(RagRepository.SourceType.NEWS), eq(URL));
    }
}
