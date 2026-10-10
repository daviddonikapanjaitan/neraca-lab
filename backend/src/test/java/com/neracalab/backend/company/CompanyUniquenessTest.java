package com.neracalab.backend.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.ingestion.mapping.FilingInfo;
import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.CompanyRow;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * company is unique per (ticker, exchange): enforced by the database and by the ingestion upsert.
 * Every test runs in a transaction that is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc       // same application context as CompanyControllerTest
@Transactional
class CompanyUniquenessTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IngestionRepository repository;

    @Test
    void secondRowForTheSameTickerAndExchangeIsRejected() {
        assertThatThrownBy(() -> insert("HRTA", "IDX"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_company_ticker_exchange");
    }

    @Test
    void lowerCaseTickerIsRejected() {
        assertThatThrownBy(() -> insert("hrta", "IDX"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_company_ticker");
    }

    @Test
    void paddedTickerIsRejected() {
        assertThatThrownBy(() -> insert(" HRTA", "IDX"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_company_ticker");
    }

    @Test
    void lowerCaseExchangeIsRejected() {
        assertThatThrownBy(() -> insert("HRTA", "idx"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_company_exchange");
    }

    @Test
    void missingExchangeIsRejected() {
        assertThatThrownBy(() -> insert("HRTA", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("exchange");
    }

    @Test
    void ingestionUpsertKeepsOneRowPerCompany() throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "HRTA", "xlsx", "FinancialStatement-2026-II-HRTA.xlsx");
        assumeTrue(Files.exists(file), "HRTA source data not available: " + file);
        IdxWorkbook workbook;
        try (InputStream in = Files.newInputStream(file)) {
            workbook = new IdxWorkbookReader().read(in, file.getFileName().toString());
        }
        FilingInfo info = new FilingMapper(workbook).info();
        long existingId = repository.findCompany("HRTA").orElseThrow().companyId();

        CompanyRow first = repository.upsertCompany(info, "Hartadinata Abadi");
        CompanyRow second = repository.upsertCompany(info, "Hartadinata Abadi");

        assertThat(first.companyId()).isEqualTo(existingId);
        assertThat(second.companyId()).isEqualTo(existingId);
        assertThat(second.ticker()).isEqualTo("HRTA");
        assertThat(jdbc.sql("SELECT count(*) FROM company WHERE ticker = 'HRTA' AND exchange = 'IDX'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void lookupNormalisesTheTicker() {
        assertThat(repository.findCompany(" hrta ")).map(CompanyRow::ticker).contains("HRTA");
    }

    private void insert(String ticker, String exchange) {
        jdbc.sql("INSERT INTO company (ticker, exchange, company_name) VALUES (:ticker, :exchange, 'Test')")
                .param("ticker", ticker).param("exchange", exchange)
                .update();
    }
}
