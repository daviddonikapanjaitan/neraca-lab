package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.screening.news.Headline;
import com.neracalab.backend.screening.news.NewsParsers;
import com.neracalab.backend.screening.news.NewsSource;

/** Date range and paging of the news collector, on the saved BBCA tag pages (no network). */
class NewsCollectorTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = NewsCollectorTest.class.getResourceAsStream("/screening/" + name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Serves the given pages; every other URL is a 404 (""). Records the requested URLs. */
    private static final class Stub extends NewsCollector {

        final Map<String, String> pages;
        final List<String> requested = new ArrayList<>();

        Stub(Map<String, String> pages) {
            super(null, EmbeddingClientTest.properties());
            this.pages = pages;
        }

        @Override
        String page(String url) {
            requested.add(url);
            return pages.getOrDefault(url, "");
        }
    }

    private static LocalDate wib(Instant at) {
        return LocalDate.ofInstant(at, NewsCollector.WIB);
    }

    @Test
    void rangeIsInclusiveInJakartaTime() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        assertThat(NewsCollector.inRange(Instant.parse("2026-09-30T17:00:00Z"), day, day)).isTrue();    // 00:00 WIB
        assertThat(NewsCollector.inRange(Instant.parse("2026-09-30T16:59:59Z"), day, day)).isFalse();
        assertThat(NewsCollector.inRange(Instant.parse("2026-10-01T16:59:59Z"), day, day)).isTrue();    // 23:59:59 WIB
        assertThat(NewsCollector.inRange(Instant.parse("2026-10-01T17:00:00Z"), day, day)).isFalse();
        assertThat(NewsCollector.inRange(null, day, day)).isTrue();     // dated later from the article
    }

    @Test
    void walksOlderPagesUntilTheRangeStart() throws IOException {
        String emiten = fixture("emitennews_tag_bbca.html");
        List<Headline> page = NewsParsers.emitenNews(emiten);
        Instant oldest = page.stream().map(Headline::publishedAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElseThrow();
        Instant newest = page.stream().map(Headline::publishedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElseThrow();

        // the first page does not reach back before the start: the next page (/9) is read too
        Stub reaching = new Stub(Map.of("https://www.emitennews.com/tag/bbca", emiten));
        NewsCollector.Collected all = reaching.collect("BBCA", "PT Bank Central Asia Tbk", wib(oldest), wib(newest), s -> { });
        assertThat(reaching.requested).contains("https://www.emitennews.com/tag/bbca", "https://www.emitennews.com/tag/bbca/9");
        assertThat(all.headlines()).hasSize(page.size());
        assertThat(all.sources()).filteredOn(s -> s.source() == NewsSource.EMITENNEWS).singleElement()
                .satisfies(s -> {
                    assertThat(s.pages()).isEqualTo(2);
                    assertThat(s.found()).isEqualTo(page.size());
                    assertThat(s.inRange()).isEqualTo(page.size());
                    assertThat(s.error()).isNull();
                });
        // newest first
        assertThat(all.headlines()).extracting(Headline::publishedAt)
                .isSortedAccordingTo(Comparator.nullsLast(Comparator.reverseOrder()));

        // a range starting after the oldest article: the first page is enough, older articles are left out
        LocalDate from = wib(oldest).plusDays(1);
        Stub recent = new Stub(Map.of("https://www.emitennews.com/tag/bbca", emiten));
        NewsCollector.Collected kept = recent.collect("BBCA", "PT Bank Central Asia Tbk", from, wib(newest), s -> { });
        assertThat(recent.requested).doesNotContain("https://www.emitennews.com/tag/bbca/9");
        assertThat(kept.headlines()).isNotEmpty().allSatisfy(h ->
                assertThat(h.publishedAt()).isAfterOrEqualTo(NewsCollector.start(from)));
        assertThat(kept.headlines()).hasSizeLessThan(page.size());
    }

    @Test
    void investorIdPagesAreNumberedFromTwo() throws IOException {
        String investor = fixture("investorid_tag_bbca.html");
        List<Headline> page = NewsParsers.investorId(investor);
        Instant oldest = page.stream().map(Headline::publishedAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElseThrow();
        Stub stub = new Stub(Map.of("https://investor.id/tag/bbca", investor));
        stub.collect("BBCA", "PT Bank Central Asia Tbk", wib(oldest), LocalDate.of(2026, 12, 31), s -> { });
        assertThat(stub.requested).contains("https://investor.id/tag/bbca", "https://investor.id/tag/bbca/2")
                .doesNotContain("https://investor.id/tag/bbca/1");
    }

    @Test
    void readsOnlyTheNewsSites() {
        Stub stub = new Stub(Map.of());
        NewsCollector.Collected collected = stub.collect("ASGR", "PT Astra Graphia Tbk", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 10, 8), s -> { });
        assertThat(collected.sources()).extracting(NewsCollector.SourceResult::source).containsExactly(
                NewsSource.EMITENNEWS, NewsSource.INVESTOR_ID, NewsSource.IDXCHANNEL, NewsSource.PASARDANA);
        assertThat(collected.headlines()).isEmpty();
        assertThat(stub.requested).allSatisfy(url -> assertThat(url).startsWith("https://")
                .containsAnyOf("emitennews.com", "investor.id", "idxchannel.com", "pasardana.id"));
    }

    @Test
    void storedTextHasTitleLeadAndBodyOnce() {
        assertThat(RagIngestionService.newsText("Laba BBCA naik", "Laba BBCA naik", "Isi berita."))
                .isEqualTo("Laba BBCA naik\n\nIsi berita.");
        assertThat(RagIngestionService.newsText("Judul", null, "Isi")).isEqualTo("Judul\n\nIsi");
        assertThat(RagIngestionService.title("FinancialStatement-2025-Tahunan-HRTA.PDF"))
                .isEqualTo("FinancialStatement-2025-Tahunan-HRTA");
    }
}
