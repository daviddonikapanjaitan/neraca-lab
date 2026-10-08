package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.screening.news.NewsHttpClient;
import com.neracalab.backend.screening.news.NewsHttpClient.NewsFetchException;

/** Which failures of a news site are asked again, and how often (the BNGA job of 2026-10-08: a 404 and a timeout). */
class NewsRetryTest {

    private static final URI ARTICLE = URI.create(
            "https://www.idxchannel.com/banking/cimb-niaga-bnga-terus-perkuat-ekosistem-digital-lebih-dari-90-persen-transaksi-lewat-octo");

    private final NewsHttpClient http = mock(NewsHttpClient.class);

    private static NewsHttpClient.Page page(int status) {
        return new NewsHttpClient.Page(status, status == 200 ? "<html></html>" : "");
    }

    private static NewsFetchException timeout() {
        return new NewsFetchException("Request to www.emitennews.com/news/x failed: HTTP connect timed out",
                new HttpConnectTimeoutException("HTTP connect timed out"));
    }

    @Test
    void retriesAnArticleThatAnswersNotFoundOnce() {
        when(http.get(ARTICLE)).thenReturn(page(404), page(200));
        assertThat(NewsRetry.get(http, ARTICLE, 3, Duration.ZERO, true).status()).isEqualTo(200);
        verify(http, times(2)).get(ARTICLE);
    }

    @Test
    void retriesATimeout() {
        when(http.get(ARTICLE)).thenThrow(timeout(), timeout()).thenReturn(page(200));
        assertThat(NewsRetry.get(http, ARTICLE, 3, Duration.ZERO, true).status()).isEqualTo(200);
        verify(http, times(3)).get(ARTICLE);
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        when(http.get(ARTICLE)).thenReturn(page(503));
        assertThat(NewsRetry.get(http, ARTICLE, 3, Duration.ZERO, true).status()).isEqualTo(503);
        verify(http, times(4)).get(ARTICLE);

        NewsHttpClient down = mock(NewsHttpClient.class);
        when(down.get(ARTICLE)).thenThrow(timeout());
        assertThatThrownBy(() -> NewsRetry.get(down, ARTICLE, 2, Duration.ZERO, true))
                .isInstanceOf(NewsFetchException.class).hasMessageContaining("timed out");
        verify(down, times(3)).get(ARTICLE);
    }

    @Test
    void doesNotRetryAnswersThatStay() {
        when(http.get(ARTICLE)).thenReturn(page(403));
        assertThat(NewsRetry.get(http, ARTICLE, 3, Duration.ZERO, true).status()).isEqualTo(403);
        verify(http, times(1)).get(ARTICLE);

        // a tag page answering 404 does not exist: not asked again
        URI tag = URI.create("https://www.emitennews.com/tag/zzzz");
        when(http.get(tag)).thenReturn(page(404));
        assertThat(NewsRetry.get(http, tag, 3, Duration.ZERO, false).status()).isEqualTo(404);
        verify(http, times(1)).get(tag);
        assertThat(NewsRetry.passing(429, false)).isTrue();
        assertThat(NewsRetry.passing(200, true)).isFalse();
    }

    @Test
    void waitsLongerBeforeEachRetry() {
        when(http.get(ARTICLE)).thenReturn(page(500), page(500), page(200));
        long start = System.nanoTime();
        assertThat(NewsRetry.get(http, ARTICLE, 3, Duration.ofMillis(50), true).status()).isEqualTo(200);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(150));  // 50 + 100
    }

    @Test
    void stopsWhenInterrupted() {
        when(http.get(ARTICLE)).thenReturn(page(500));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> NewsRetry.get(http, ARTICLE, 3, Duration.ofSeconds(5), true))
                    .isInstanceOf(NewsFetchException.class).hasMessageContaining("Interrupted");
            verify(http, times(1)).get(ARTICLE);
        } finally {
            Thread.interrupted();
        }
    }
}
