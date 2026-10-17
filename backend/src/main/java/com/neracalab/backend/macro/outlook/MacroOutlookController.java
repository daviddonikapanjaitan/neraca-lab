package com.neracalab.backend.macro.outlook;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;

/**
 * The Macro page (permission COMPANIES: the macro data are market data, already shown on Companies > Technicals):
 * <ul>
 *   <li>{@code GET /api/v1/macro/outlook/countries} - the countries and their indexes (Indonesia: IHSG, LQ45; the
 *       United States and China coming later)</li>
 *   <li>{@code GET /api/v1/macro/outlook?country=ID&index=IHSG&range=1Y} - the index line, the macro panel (a chart and
 *       a summary per series, the liquidity and risk regimes), the Paul Tudor Jones and Druckenmiller summaries and the
 *       conclusion (investability score, when to buy and when to sell); {@link MacroOutlook}</li>
 * </ul>
 */
@RestController
@RequiresPermission(Permission.COMPANIES)
@RequestMapping(path = "/api/v1/macro/outlook", produces = MediaType.APPLICATION_JSON_VALUE)
public class MacroOutlookController {

    private final MacroOutlookService service;

    public MacroOutlookController(MacroOutlookService service) {
        this.service = service;
    }

    @GetMapping("/countries")
    public List<Map<String, Object>> countries() {
        return service.countries();
    }

    @GetMapping
    public Map<String, Object> outlook(@RequestParam(name = "country", defaultValue = "ID") String country,
                                       @RequestParam(name = "index", required = false) String index,
                                       @RequestParam(name = "range", defaultValue = "1Y") String range) {
        return service.outlook(country, index, range);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }
}
