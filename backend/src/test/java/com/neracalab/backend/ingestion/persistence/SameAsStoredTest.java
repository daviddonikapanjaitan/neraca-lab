package com.neracalab.backend.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Filing values are compared with stored values at the column's scale: Postgres rounds INDY's USD
 * EPS 0.0868828938473089 to NUMERIC(20,8) 0.08688289, which is the filing's value as stored.
 */
class SameAsStoredTest {

    private static boolean same(String stored, String filed) {
        return IngestionRepository.sameAsStored(stored == null ? null : new BigDecimal(stored),
                filed == null ? null : new BigDecimal(filed));
    }

    @Test
    void comparesAtTheStoredScale() {
        assertThat(same("0.08688289", "0.0868828938473089")).isTrue();     // INDY FY2022 EPS
        assertThat(same("0.02300421", "0.0230042062839776")).isTrue();     // INDY FY2023 EPS (rounded up)
        assertThat(same("3613292000000.0000", "3613292000000")).isTrue();
        assertThat(same("0.00190000", "0.0019")).isTrue();
    }

    @Test
    void stillReportsRealDifferences() {
        assertThat(same("0.08688288", "0.0868828938473089")).isFalse();
        assertThat(same("3613292000000.0000", "3613292000001")).isFalse();
        assertThat(same("0.0019", "0.00195")).isFalse();                  // stored has fewer decimals than its value needs
        assertThat(same(null, "1")).isFalse();
        assertThat(same("1", null)).isFalse();
        assertThat(same(null, null)).isTrue();
    }
}
