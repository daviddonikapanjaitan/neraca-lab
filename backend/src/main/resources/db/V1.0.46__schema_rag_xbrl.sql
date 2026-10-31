-- =====================================================================
-- Neraca Lab - the IDX XBRL financial statement workbooks in the RAG vector store
--
--   rag_document : source type XBRL, document type FINANCIAL_STATEMENT: one document per uploaded workbook
--                  (FinancialStatement-2025-Tahunan-HRTA.xlsx, Ingestion > IDX XBRL) and company, keyed by the file's
--                  SHA-256 like a PDF, linked to the stored file (ingestion_file); written by the upload job once the
--                  statements are stored (FinancialStatementQueue, XbrlRagService), title = the file name without
--                  .xlsx, no pages
--   rag_chunk    : the workbook's text (XbrlDocumentText: every sheet, each row with an amount, a date or a label,
--                  value and English label as one line), chunked per sheet; every chunk starts with the document
--                  title, the sheet's title and its value columns
--
-- The constraints that admit the source type are in V1.0.29 (ck_rag_document_company_type) and V1.0.33
-- (ck_rag_document_source_type), which re-create them on every start before this script.
-- Executed on every application start by Spring SQL init, after V1.0.40 (the last schema script before it). Every
-- statement is idempotent.
-- =====================================================================

COMMENT ON TABLE  rag_document IS 'A source document of the RAG vector store: an uploaded PDF, a news article or an Excel ownership document of a company, an IDX XBRL financial statement workbook of a company (V1.0.46), or a macro PDF of a country (V1.0.28).';
COMMENT ON COLUMN rag_document.source_type IS 'PDF: an uploaded PDF; NEWS: a news article; XLSX: a document built from uploaded Excel ownership lists (V1.0.33); XBRL: an uploaded IDX XBRL financial statement workbook (.xlsx, V1.0.46).';
COMMENT ON COLUMN rag_document.source_key IS 'PDF and XBRL: SHA-256 of the file (one document per file and company or country); NEWS: the article URL; XLSX: the Excel document type (OWNERSHIP_5_PERCENT: one document per company, rebuilt from every stored list).';
COMMENT ON COLUMN rag_document.document_type IS
    'Company PDF: FINANCIAL_STATEMENT (default) or REGISTRATION (monthly registration report of securities holders, V1.0.29); XBRL workbook: FINANCIAL_STATEMENT (V1.0.46); macro document: MONETARY_POLICY, FINANCIAL_STABILITY, FISCAL, EXTERNAL_SECTOR, ECONOMIC_OUTLOOK, STATISTICS, DEVELOPMENT_PLAN, RESEARCH (opinion) or OTHER; news and Excel ownership documents: NULL.';
COMMENT ON COLUMN rag_document.job_id IS 'ingestion_job that last stored the document (the upload job of an XBRL workbook; NULL for the workbooks stored before V1.0.46, embedded afterwards).';
COMMENT ON COLUMN rag_chunk.page_from IS 'PDF: first page of the chunk (1-based); NEWS, XLSX and XBRL: NULL.';
