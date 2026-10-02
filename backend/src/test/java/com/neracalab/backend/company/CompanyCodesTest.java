package com.neracalab.backend.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.neracalab.backend.company.Exchange.UnsupportedExchangeException;
import com.neracalab.backend.company.Tickers.InvalidTickerException;

class CompanyCodesTest {

    @Test
    void tickersAreTrimmedAndUpperCased() {
        assertThat(Tickers.normalize("HRTA")).isEqualTo("HRTA");
        assertThat(Tickers.normalize(" hrta ")).isEqualTo("HRTA");
        assertThat(Tickers.normalize("brk.b")).isEqualTo("BRK.B");
        assertThat(Tickers.normalize("0700")).isEqualTo("0700");
        assertThat(Tickers.normalize("A".repeat(20))).hasSize(20);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "HR TA", ".HRTA", "-HRTA", "HRTA;", "HRTA'", "ABCDEFGHIJKLMNOPQRSTU"})
    void invalidTickersAreRejected(String ticker) {
        assertThatThrownBy(() -> Tickers.normalize(ticker)).isInstanceOf(InvalidTickerException.class);
    }

    @Test
    void exchangeCodesAreCaseInsensitive() {
        assertThat(Exchange.of("IDX")).isEqualTo(Exchange.IDX);
        assertThat(Exchange.of(" idx ")).isEqualTo(Exchange.IDX);
        assertThat(Exchange.codes()).contains("IDX");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"NYSE", "ID X", "XIDX"})
    void unknownExchangesAreRejected(String code) {
        assertThatThrownBy(() -> Exchange.of(code))
                .isInstanceOf(UnsupportedExchangeException.class)
                .hasMessageContaining("supported: IDX");
    }
}
