package com.neracalab.backend.price.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.price.provider.FxRateProvider.FxRate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parsing of a real Frankfurter response (ECB reference rates, USD/IDR 2026-09-28..2026-10-05). */
class EcbFxRateProviderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void parsesQuoteUnitsPerBaseUnitByDate() throws IOException {
        JsonNode root = JSON.readTree(resource("/price/frankfurter_USD_IDR_2026-09-28_2026-10-05.json"));

        // business days only: no 2026-10-03 / 2026-10-04 (weekend)
        assertThat(EcbFxRateProvider.parse(root, "USD", "IDR")).containsExactly(
                rate("2026-09-28", "17977"), rate("2026-09-29", "17922"), rate("2026-09-30", "17891"),
                rate("2026-10-01", "17946"), rate("2026-10-02", "17950"), rate("2026-10-05", "17913"));
    }

    @Test
    void rejectsAResponseForAnotherBase() {
        JsonNode root = JSON.readTree("{\"base\":\"EUR\",\"rates\":{\"2026-10-02\":{\"IDR\":20900}}}");

        assertThatThrownBy(() -> EcbFxRateProvider.parse(root, "USD", "IDR"))
                .isInstanceOf(PriceProviderException.class)
                .hasMessageContaining("expected USD/IDR");
    }

    @Test
    void rejectsAResponseWithoutRates() {
        JsonNode root = JSON.readTree("{\"message\":\"not found\"}");

        assertThatThrownBy(() -> EcbFxRateProvider.parse(root, "USD", "IDR"))
                .isInstanceOf(PriceProviderException.class);
    }

    @Test
    void ignoresDaysWithoutTheQuoteOrWithANonPositiveRate() {
        JsonNode root = JSON.readTree("""
                {"base":"USD","rates":{"2026-10-01":{"EUR":0.86},"2026-10-02":{"IDR":0},"2026-10-05":{"IDR":17913.5}}}""");

        assertThat(EcbFxRateProvider.parse(root, "USD", "IDR")).containsExactly(rate("2026-10-05", "17913.5"));
    }

    private static FxRate rate(String date, String value) {
        return new FxRate(LocalDate.parse(date), new BigDecimal(value));
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = EcbFxRateProviderTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
