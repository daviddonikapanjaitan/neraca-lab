package com.neracalab.backend.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;

/**
 * The write lock of a company, which the write steps of filings stored at the same time hold: one per company,
 * so two filings of one company never write at the same moment while other companies are not held up.
 */
class CompanyWriteLockTest {

    private final IngestionRepository repository = new IngestionRepository(null, null, null);

    @Test
    void oneLockPerCompanyWhateverTheSpellingOfTheTicker() {
        ReentrantLock mapa = repository.companyLock("MAPA");

        assertThat(repository.companyLock("MAPA")).isSameAs(mapa);
        assertThat(repository.companyLock(" mapa ")).isSameAs(mapa);
        assertThat(repository.companyLock("HRTA")).isNotSameAs(mapa);
        assertThat(repository.companyLock(null)).isSameAs(repository.companyLock(""));
    }

    @Test
    void aWriteStepWaitsWhileAnotherFilingOfTheCompanyHoldsTheLock() throws Exception {
        ReentrantLock held = repository.companyLock("MAPA");
        held.lock();
        AtomicBoolean sameCompanyWrote = new AtomicBoolean();
        CountDownLatch otherCompanyWrote = new CountDownLatch(1);
        CountDownLatch sameCompanyDone = new CountDownLatch(1);
        Thread sameCompany = new Thread(() -> write("MAPA", () -> {
            sameCompanyWrote.set(true);
            sameCompanyDone.countDown();
        }));
        Thread otherCompany = new Thread(() -> write("HRTA", otherCompanyWrote::countDown));
        try {
            sameCompany.start();
            otherCompany.start();

            assertThat(otherCompanyWrote.await(5, TimeUnit.SECONDS)).as("another company writes meanwhile").isTrue();
            Thread.sleep(100);
            assertThat(sameCompanyWrote).as("a filing of the same company waits").isFalse();
        } finally {
            held.unlock();
        }
        assertThat(sameCompanyDone.await(5, TimeUnit.SECONDS)).as("it writes once the lock is free").isTrue();
        sameCompany.join(5000);
        otherCompany.join(5000);
    }

    private void write(String ticker, Runnable step) {
        ReentrantLock lock = repository.companyLock(ticker);
        lock.lock();
        try {
            step.run();
        } finally {
            lock.unlock();
        }
    }
}
