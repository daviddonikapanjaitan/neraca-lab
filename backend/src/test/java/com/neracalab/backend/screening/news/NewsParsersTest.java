package com.neracalab.backend.screening.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.screening.news.NewsParsers.ArticleText;

/**
 * Parsers of the crawled news sites against excerpts of their pages (October 2026: the headline lists
 * the parsers read) and a synthetic article page with the structure of an IDX Channel article.
 */
class NewsParsersTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = NewsParsersTest.class.getResourceAsStream("/screening/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void emitenNewsTagPage() throws IOException {
        List<Headline> headlines = NewsParsers.emitenNews(fixture("emitennews_tag_bbca.html"));
        assertThat(headlines).hasSize(9);   // the result cards only, not the trending sidebar
        assertThat(headlines).allSatisfy(h -> {
            assertThat(h.url()).startsWith("https://www.emitennews.com/news/");
            assertThat(h.source()).isEqualTo(NewsSource.EMITENNEWS);
            assertThat(h.title()).isNotBlank();
        });
        Headline shortSelling = headlines.stream()
                .filter(h -> h.url().endsWith("/ada-bbca-hingga-asii-bei-umumkan-5-saham-short-selling-bulan-oktober"))
                .findFirst().orElseThrow();
        assertThat(shortSelling.title()).isEqualTo("Ada BBCA hingga ASII, BEI Umumkan 5 Saham Short Selling Bulan Oktober");
        assertThat(shortSelling.publishedAt()).isEqualTo(Instant.parse("2026-09-28T14:01:00Z"));   // 21:01 WIB
        assertThat(shortSelling.description()).startsWith("EmitenNews.com - Bursa Efek Indonesia");
    }

    @Test
    void idxChannelTagPage() throws IOException {
        List<Headline> headlines = NewsParsers.idxChannel(fixture("idxchannel_tag_bbca.html"));
        assertThat(headlines).hasSize(9);
        assertThat(headlines.get(0).title()).isEqualTo("Gandeng AIA, Bank BCA Luncurkan Asuransi Jiwa Berdenominasi US Dolar");
        assertThat(headlines.get(0).url())
                .isEqualTo("https://www.idxchannel.com/banking/gandeng-aia-bank-bca-luncurkan-asuransi-jiwa-berdenominasi-us-dolar");
        assertThat(headlines.get(0).publishedAt()).isEqualTo(Instant.parse("2026-10-02T11:16:00Z"));
        assertThat(headlines).extracting(Headline::source).containsOnly(NewsSource.IDXCHANNEL);
    }

    @Test
    void investorIdTagPageSkipsTheSidebar() throws IOException {
        List<Headline> headlines = NewsParsers.investorId(fixture("investorid_tag_bbca.html"));
        assertThat(headlines).isNotEmpty();
        assertThat(headlines).allSatisfy(h -> assertThat(h.url()).matches("https://investor\\.id/[a-z-]+/\\d+/[a-z0-9-]+"));
        Headline first = headlines.get(0);
        assertThat(first.url()).isEqualTo("https://investor.id/market/456498/belanja-asing-tertuju-ke-10saham");
        assertThat(first.title()).startsWith("Belanja Asing Tertuju ke 10");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-03T03:00:00Z"));          // 3 Okt 2026 10:00 WIB
        assertThat(first.description()).contains("BBCA");
        // editorial teasers of the sidebar are not tag results
        assertThat(headlines).extracting(Headline::url)
                .doesNotContain("https://investor.id/editorial/451009/stabilitas-untuk-pertumbuhan");
    }

    @Test
    void pasardanaListingTakesTheDateFromThePath() throws IOException {
        List<Headline> headlines = NewsParsers.pasardana(fixture("pasardana_news.html"));
        assertThat(headlines).hasSizeGreaterThanOrEqualTo(10);
        Headline ipo = headlines.stream().filter(h -> h.url().contains("bei-ungkap-pipeline-terbaru")).findFirst().orElseThrow();
        assertThat(ipo.url()).isEqualTo(
                "https://pasardana.id/news/2026/10/3/bei-ungkap-pipeline-terbaru-7-calon-emiten-antre-ipo-24-emisi-obligasi-siap-meluncur");
        assertThat(ipo.title()).isEqualTo("BEI Ungkap Pipeline Terbaru! 7 Calon Emiten Antre IPO, 24 Emisi Obligasi Siap Meluncur");
        assertThat(ipo.publishedAt()).isEqualTo(Instant.parse("2026-10-02T17:00:00Z"));            // 3 Oct, 00:00 WIB
    }

    @Test
    void articleTitleDateAndBody() throws IOException {
        String url = "https://www.idxchannel.com/market-news/analis-nilai-crossing-ratusan-juta-saham-bbca-bukan-sinyal-akuisisi";
        ArticleText article = NewsParsers.article(fixture("idxchannel_article.html"), url, 800);
        assertThat(article.title()).isEqualTo("Analis Nilai Crossing Ratusan Juta Saham BBCA Bukan Sinyal Akuisisi");
        assertThat(article.publishedAt()).isEqualTo(Instant.parse("2026-10-01T09:12:00Z"));
        assertThat(article.description()).contains("Bank Central Asia");
        assertThat(article.body()).isNotBlank().hasSizeLessThanOrEqualTo(801);
    }

    @Test
    void datesInJakartaTime() {
        assertThat(NewsParsers.dmyTime("28/09/2026, 21:01 WIB")).isEqualTo(Instant.parse("2026-09-28T14:01:00Z"));
        assertThat(NewsParsers.dayMonthYear("3 Okt 2026 | 10:00 WIB")).isEqualTo(Instant.parse("2026-10-03T03:00:00Z"));
        assertThat(NewsParsers.dayMonthYear("03 Oktober 2026, 10:40")).isEqualTo(Instant.parse("2026-10-03T03:40:00Z"));
        assertThat(NewsParsers.dayMonthYear("12 Agu 2026")).isEqualTo(Instant.parse("2026-08-11T17:00:00Z"));
        assertThat(NewsParsers.dayMonthYear("12 Xyz 2026")).isNull();
        assertThat(NewsParsers.dmyTime(null)).isNull();
        assertThat(NewsParsers.isoInstant("2026-10-02T14:43:17.322Z")).isEqualTo(Instant.parse("2026-10-02T14:43:17.322Z"));
        assertThat(NewsParsers.isoInstant("2026-10-01T16:12:00+07:00")).isEqualTo(Instant.parse("2026-10-01T09:12:00Z"));
    }

    @Test
    void layoutChangesYieldNoHeadlinesInsteadOfErrors() {
        assertThat(NewsParsers.emitenNews("<html><body><p>new layout</p></body></html>")).isEmpty();
        assertThat(NewsParsers.idxChannel("")).isEmpty();
        assertThat(NewsParsers.investorId("<html></html>")).isEmpty();
        assertThat(NewsParsers.pasardana("<a href=\"/news/2026/13/40/x\">bad date link with a long enough title</a>"))
                .allSatisfy(h -> assertThat(h.publishedAt()).isNull());
    }

    @Test
    void headlineRelevanceForPasardana() {
        Headline adhi = new Headline("https://pasardana.id/news/2026/10/2/adhi-lepas-dua-anak-usaha", NewsSource.PASARDANA,
                "ADHI Lepas Dua Anak Usaha Sekaligus", null, null);
        assertThat(NewsService.mentions(adhi, "ADHI", "PT Adhi Karya (Persero) Tbk")).isTrue();
        assertThat(NewsService.mentions(adhi, "BBCA", "PT Bank Central Asia Tbk")).isFalse();
        Headline byName = new Headline("https://pasardana.id/news/2026/10/2/x", NewsSource.PASARDANA,
                "Laba Bank Central Asia Naik 10 Persen", null, null);
        assertThat(NewsService.mentions(byName, "BBCA", "PT Bank Central Asia Tbk")).isTrue();
        assertThat(NewsService.shortName("PT Bank Rakyat Indonesia (Persero) Tbk.")).isEqualTo("Bank Rakyat Indonesia");
    }
}
