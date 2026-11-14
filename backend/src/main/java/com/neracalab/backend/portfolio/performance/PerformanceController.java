package com.neracalab.backend.portfolio.performance;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.portfolio.performance.PerformanceViews.Performance;
import com.neracalab.backend.portfolio.performance.PerformanceViews.Saved;
import com.neracalab.backend.portfolio.performance.PerformanceViews.YearRequest;

import jakarta.validation.Valid;

/**
 * Portfolio &gt; Performance of the signed-in user (permission COMPANIES; each user sees only their own):
 * <ul>
 *   <li>{@code GET /api/v1/portfolio/performance?range=ALL|2022|...} - the chart (portfolio, IHSG and LQ45 since the
 *       last close of the year before the range), the years of the sheet tracking_performance, the latest daily
 *       snapshots and when the next one is saved</li>
 *   <li>{@code PUT / DELETE /api/v1/portfolio/performance/years/{year}} - a year's row (start capital, new investment,
 *       dividend, the year-end figures the sheet recorded)</li>
 *   <li>{@code POST /api/v1/portfolio/performance/snapshots} - save today's snapshot now (at or after 17:00 WIB on a
 *       trading day; 409 before, on a weekend or an exchange holiday); the schedule does it every trading day</li>
 * </ul>
 * Every call answers the page as it now stands ({@code range} keeps the chart's range).
 */
@RestController
@RequestMapping(path = "/api/v1/portfolio/performance", produces = MediaType.APPLICATION_JSON_VALUE)
public class PerformanceController {

    private final PerformanceService service;

    public PerformanceController(PerformanceService service) {
        this.service = service;
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping
    public Performance performance(@RequestParam(name = "range", required = false) String range,
                                   AuthenticatedUser user) {
        return service.performance(user.userId(), range);
    }

    @RequiresPermission(Permission.COMPANIES)
    @PutMapping(path = "/years/{year}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Performance saveYear(@PathVariable("year") int year, @Valid @RequestBody YearRequest request,
                                @RequestParam(name = "range", required = false) String range,
                                AuthenticatedUser user) {
        return service.saveYear(user.userId(), year, request, range);
    }

    @RequiresPermission(Permission.COMPANIES)
    @DeleteMapping("/years/{year}")
    public Performance deleteYear(@PathVariable("year") int year,
                                  @RequestParam(name = "range", required = false) String range,
                                  AuthenticatedUser user) {
        return service.deleteYear(user.userId(), year, range);
    }

    @RequiresPermission(Permission.COMPANIES)
    @PostMapping("/snapshots")
    public Saved saveToday(@RequestParam(name = "range", required = false) String range, AuthenticatedUser user) {
        return service.saveToday(user.userId(), range);
    }
}
