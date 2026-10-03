package com.neracalab.backend.job;

/** Kind of ingestion process recorded in {@code ingestion_job.job_type}. */
public enum IngestionJobType {

    /** IDX XBRL workbook (.xlsx) upload, stored by the AI agent */
    FINANCIAL_STATEMENT,
    /** daily prices of one company from the price provider, plus the valuation refresh */
    PRICE
}
