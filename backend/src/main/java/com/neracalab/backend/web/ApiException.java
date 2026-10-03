package com.neracalab.backend.web;

import org.springframework.http.HttpStatus;

/**
 * An error answered as a ProblemDetail with this status, title and detail (the exception message)
 * by {@link ApiExceptionHandler}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    public ApiException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** 400 */
    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Invalid request", detail);
    }

    /** 404 */
    public static ApiException notFound(String title, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, title, detail);
    }

    /** 409 */
    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "Conflict", detail);
    }
}
