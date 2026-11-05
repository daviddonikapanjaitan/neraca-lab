package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The share counts of V1.0.48__data_KSEI_share_capital_shares.sql (run on every start): share capital / par value,
 * inside the issued count of KSEI's 1% list of 2026-10-30. Against the Docker Postgres; a company not stored is skipped.
 */
@SpringBootTest
class ShareCapitalCountsTest {

    @Autowired
    private JdbcClient jdbc;

    /** DOSS: 69,000,000,000 / Rp 40 (KSEI 1,724,890,830 - 1,725,150,877; 9M 2026 profit / EPS 1,725,273,476). */
    @Test
    void aCountFromTheShareCapitalAndKsei() {
        assertCount("DOSS", "2025-12-31", "1725000000");
        assertCount("DOSS", "2026-09-30", "1725000000");
        assertCount("PSDN", "2021-12-31", "1440000000");
    }

    /** LPKR holds treasury shares under 2% of its issued count (9M 2026 EPS count 70,869,050,343): the issued count. */
    @Test
    void aTreasuryHolderUnderTwoPercentGetsItsIssuedCount() {
        assertCount("LPKR", "2026-09-30", "70898020000");
        assertCount("WIKA", "2025-12-31", "39873063860");
    }

    /** V1.0.49: counts stored at par Rp 1 (VICO x100, MKTR x10) are the share capital at the par KSEI confirms. */
    @Test
    void aCountFiledAtParOneIsCorrected() {
        assertCount("VICO", "2025-12-31", "15217075660");
        assertCount("MKTR", "2026-09-30", "12060428600");
        assertCount("WAPO", "2026-09-30", "1240923111");
    }

    /** V1.0.50: PBSA's split 1:2 of 2026-10-13 (price 820 -> 422.50, KSEI 6.0 bn): earlier counts split-adjusted. */
    @Test
    void aSplitRestatesTheEarlierCounts() {
        assertCount("PBSA", "2026-10-13", "6000000000");
        assertCount("PBSA", "2025-12-31", "6000000000");
        assertCount("MORA", "2026-09-30", "47774192736");
    }

    private void assertCount(String ticker, String date, String expected) {
        List<BigDecimal> count = jdbc.sql("""
                        SELECT ss.shares_outstanding FROM share_snapshot ss
                        JOIN company c ON c.company_id = ss.company_id
                        WHERE c.ticker = :t AND c.exchange = 'IDX' AND ss.snapshot_date = CAST(:d AS date)""")
                .param("t", ticker).param("d", date)
                .query(BigDecimal.class).list();
        assumeTrue(!count.isEmpty(), ticker + " " + date + " is not stored");
        assertThat(count.getFirst()).isEqualByComparingTo(expected);
    }
}
