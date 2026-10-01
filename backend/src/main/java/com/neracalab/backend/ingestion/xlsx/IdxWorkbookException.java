package com.neracalab.backend.ingestion.xlsx;

/** The uploaded file is not a readable IDX XBRL financial statement workbook. */
public class IdxWorkbookException extends RuntimeException {

    public IdxWorkbookException(String message) {
        super(message);
    }

    public IdxWorkbookException(String message, Throwable cause) {
        super(message, cause);
    }
}
