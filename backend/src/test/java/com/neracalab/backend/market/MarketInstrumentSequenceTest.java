package com.neracalab.backend.market;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * V1.0.26 runs at every start (test contexts included). {@code market_instrument.instrument_id} is a SMALLSERIAL, and
 * its former INSERT ... ON CONFLICT drew a sequence value for every one of the 35 instruments at each run, also for the
 * existing ones, until the sequence ran out at 32,767 on 2026-10-31 and no application context could start any more.
 * Now a run draws no value for an existing instrument. Against the Docker Postgres.
 */
@SpringBootTest
class MarketInstrumentSequenceTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void runningTheScriptAgainDrawsNoSequenceValue() {
        run();
        long before = sequence();
        long instruments = jdbc.sql("SELECT count(*) FROM market_instrument").query(Long.class).single();

        run();
        run();

        assertThat(sequence()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM market_instrument").query(Long.class).single()).isEqualTo(instruments);
        // the sequence stands at the highest id in use, far from the SMALLSERIAL's 32,767
        assertThat(before).isEqualTo(jdbc.sql("SELECT max(instrument_id) FROM market_instrument").query(Long.class).single());
    }

    private void run() {
        new ResourceDatabasePopulator(new ClassPathResource("db/V1.0.26__data_markets_macro.sql")).execute(dataSource);
    }

    private long sequence() {
        return jdbc.sql("SELECT last_value FROM market_instrument_instrument_id_seq").query(Long.class).single();
    }
}
