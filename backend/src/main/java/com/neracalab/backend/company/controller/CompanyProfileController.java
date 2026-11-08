package com.neracalab.backend.company.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.company.CompanyService.CompanyNotFoundException;
import com.neracalab.backend.company.Exchange.UnsupportedExchangeException;
import com.neracalab.backend.company.Tickers.InvalidTickerException;
import com.neracalab.backend.company.profile.CompanyProfileService;
import com.neracalab.backend.company.profile.CompanyProfileViews.CompanyProfile;
import com.neracalab.backend.company.profile.CompanyProfileViews.Status;
import com.neracalab.backend.company.profile.CompanyProfileViews.UploadResult;
import com.neracalab.backend.company.profile.IdxProfileParser.InvalidProfileFileException;

/**
 * IDX company profiles (listing date, listing board, IDX-IC sector, dividends).
 * <ul>
 *   <li>{@code POST /api/v1/companies/profiles/upload?exchange=IDX} (multipart: file) - the JSON a browser session
 *       collected on idx.co.id ({@code scripts/idx_company_profiles.js}); INGESTION</li>
 *   <li>{@code GET /api/v1/companies/profiles/status?exchange=IDX} - counts, the last upload, companies without a
 *       listing date; INGESTION</li>
 *   <li>{@code GET /api/v1/companies/{exchange}/{ticker}/profile} - the Profile tab of a company: profile and dividend
 *       table; COMPANIES</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/companies", produces = MediaType.APPLICATION_JSON_VALUE)
public class CompanyProfileController {

    private final CompanyProfileService service;

    public CompanyProfileController(CompanyProfileService service) {
        this.service = service;
    }

    @RequiresPermission(Permission.INGESTION)
    @PostMapping(path = "/profiles/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResult upload(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                               @RequestPart("file") MultipartFile file, AuthenticatedUser user) throws IOException {
        return service.upload(exchange, file.getOriginalFilename(), file.getBytes(), user.username());
    }

    @RequiresPermission(Permission.INGESTION)
    @GetMapping("/profiles/status")
    public Status status(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange) {
        return service.status(exchange);
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/{exchange}/{ticker}/profile")
    public CompanyProfile profile(@PathVariable("exchange") String exchange, @PathVariable("ticker") String ticker) {
        return service.companyProfile(exchange, ticker);
    }

    @ExceptionHandler(InvalidProfileFileException.class)
    ProblemDetail invalidFile(InvalidProfileFileException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("Rejected");
        return problem;
    }

    @ExceptionHandler(UnsupportedExchangeException.class)
    ProblemDetail unsupportedExchange(UnsupportedExchangeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Unsupported exchange");
        problem.setProperty("supportedExchanges", e.supported());
        return problem;
    }

    @ExceptionHandler(InvalidTickerException.class)
    ProblemDetail invalidTicker(InvalidTickerException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid ticker");
        return problem;
    }

    @ExceptionHandler(CompanyNotFoundException.class)
    ProblemDetail notFound(CompanyNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Company not found");
        return problem;
    }
}
