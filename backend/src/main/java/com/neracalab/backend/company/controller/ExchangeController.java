package com.neracalab.backend.company.controller;

import java.util.Arrays;
import java.util.List;

import com.neracalab.backend.auth.RequiresLogin;
import com.neracalab.backend.company.Exchange;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/exchanges}: the exchanges the company APIs accept, e.g. for an exchange filter (any logged-in user). */
@RestController
@RequiresLogin
@RequestMapping(path = "/api/v1/exchanges", produces = MediaType.APPLICATION_JSON_VALUE)
public class ExchangeController {

    /** @param code value for the {@code exchange} parameter / path segment of the company APIs */
    public record ExchangeResponse(String code, String name, String country) {
    }

    @GetMapping
    public List<ExchangeResponse> list() {
        return Arrays.stream(Exchange.values())
                .map(e -> new ExchangeResponse(e.code(), e.displayName(), e.country()))
                .toList();
    }
}
