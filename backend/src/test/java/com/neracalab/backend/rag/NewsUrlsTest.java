package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The search results of the ASGR news job (2026-01-01 .. 2026-10-08): which ones are articles. */
class NewsUrlsTest {

    @Test
    void keepsNewsArticles() {
        assertThat(NewsUrls.isArticle(
                "https://investasi.kontan.co.id/news/astra-graphia-asgr-tebar-dividen-di-atas-laba-seberapa-menarik-sahamnya",
                "ASGR")).isTrue();
        assertThat(NewsUrls.isArticle("https://id.tradingview.com/news/kontan:d09897a6c87ea:0", "ASGR")).isTrue();
        assertThat(NewsUrls.isArticle(
                "https://www.emitennews.com/news/asgr-jadwal-dividen-rp297-per-helai-yield-1338-persen", "ASGR")).isTrue();
        assertThat(NewsUrls.isArticle("https://investor.id/market/456062/ihsg-sempat-turun-tapi-langsung-loncat-asgr-hingga-bksl-ngebut",
                "ASGR")).isTrue();
        // an article in a section named like a listing: its slug wins
        assertThat(NewsUrls.isArticle("https://example.co.id/saham/astra-graphia-bagikan-dividen-interim-jumbo", "ASGR"))
                .isTrue();
    }

    @Test
    void dropsQuoteProfileAndListingPages() {
        assertThat(NewsUrls.isArticle("https://finance.yahoo.com/quote/ASGR.JK", "ASGR")).isFalse();
        assertThat(NewsUrls.isArticle("https://invest.cermati.com/saham/asgr", "ASGR")).isFalse();
        assertThat(NewsUrls.isArticle("https://ajaib.co.id/saham/aset/ASGR", "ASGR")).isFalse();
        assertThat(NewsUrls.isArticle(
                "https://www.idx.co.id/id/perusahaan-tercatat/profil-perusahaan-tercatat/ASGR", "ASGR")).isFalse();
        assertThat(NewsUrls.isArticle("https://www.bisnis.com/topic/8732/astra-graphia", "ASGR")).isFalse();
        assertThat(NewsUrls.isArticle("https://www.emitennews.com/tag/asgr", "ASGR")).isFalse();
        assertThat(NewsUrls.isArticle("https://www.example.com/", "ASGR")).isFalse();
        assertThat(NewsUrls.isArticle("https://www.example.com/ASGR", "ASGR")).isFalse();
    }

    @Test
    void dropsSocialMedia() {
        assertThat(NewsUrls.isArticle("https://www.instagram.com/p/DXMIRCQkxP1?hl=en", "ASGR")).isFalse();
        assertThat(NewsUrls.isArticle("https://www.instagram.com/reel/DXN_X0jSGAS?hl=en", "ASGR")).isFalse();
        assertThat(NewsUrls.isArticle("https://www.facebook.com/IDXChannelcom/videos/pt-astra-graphia-tbk-asgr-menetapkan-pembagian-dividen/1964673681103220",
                "ASGR")).isFalse();
        assertThat(NewsUrls.isArticle("https://m.youtube.com/watch?v=abc", "ASGR")).isFalse();
        assertThat(NewsUrls.isArticle("ftp://example.com/news/a-b-c-d", "ASGR")).isFalse();
        assertThat(NewsUrls.isArticle("not a url", "ASGR")).isFalse();
    }
}
