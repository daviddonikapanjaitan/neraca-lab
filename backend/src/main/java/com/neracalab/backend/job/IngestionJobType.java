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
    ANALYSIS
}
