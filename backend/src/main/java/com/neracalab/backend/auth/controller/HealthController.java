package com.neracalab.backend.auth.controller;

import java.util.Map;

import com.neracalab.backend.auth.PublicAccess;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/health}: the only public read endpoint, for container health checks. Returns no data. */
@RestController
public class HealthController {

    @PublicAccess
    @GetMapping(path = "/api/v1/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
