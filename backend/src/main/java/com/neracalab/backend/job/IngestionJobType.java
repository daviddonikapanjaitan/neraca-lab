package com.neracalab.backend.job;

/** Kind of background process recorded in {@code ingestion_job.job_type}. */
public enum IngestionJobType {

    /** IDX XBRL workbook (.xlsx) upload, stored by the AI agent */
    FINANCIAL_STATEMENT,
    /** daily prices of one company from the price provider, plus the valuation refresh */
    PRICE,
    /** screening data ETL: market data and fundamentals of every listing of an exchange (Yahoo Finance) */
    FUNDAMENTALS,
    /** AI stock screening run: quantitative pre-screen, investor agents, synthesis, report */
    SCREENING,
    /** PDF document of a company into the RAG vector store (text chunks with embeddings) */
    RAG_PDF,
    /** news of a company within a date range into the RAG vector store */
    RAG_NEWS,
    /** AI analysis of one stock: research agent over its stored documents, investor agents, synthesis, report */
    ANALYSIS,
    /** one macro series (BI rate, US 10Y, Fed assets, ...) from its source into macro_series (technical analysis) */
    MACRO,
    /** AI macro analysis of a country's stock market: research agent, the four macro agents, synthesis, report */
    MACRO_ANALYSIS,
    /** daily bars of every listing of the screening universe (Yahoo Finance): the price movements of the technical screening */
    LISTING_PRICES,
    /** AI technical screening run: rules pre-screen, the four technical agents, synthesis, trade plans */
    TECHNICAL_SCREENING,
    /**
     * Excel document into the database and the RAG vector store: KSEI's daily list of the shareholders of more than
     * 5% (Companies > Ownership (>=5%))
     */
    RAG_XLSX
}
