package com.neracalab.backend.macro.outlook;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.macro.MacroRepository;
import com.neracalab.backend.macro.MacroService;
import com.neracalab.backend.macro.MacroService.SeriesNotFoundException;
import com.neracalab.backend.macro.MacroService.SeriesStatus;

/**
 * Macro › Data (permission COMPANIES, as the Macro page): every series of Ingestion › Macro as a chart. Read only; the
 * fetches and uploads stay on Ingestion › Macro (permission INGESTION).
 * <ul>
 *   <li>{@code GET /api/v1/macro/data/series} - the series with what is stored of each</li>
 *   <li>{@code GET /api/v1/macro/data/series/{code}?range=1Y} - one series' chart, statistics, changes and latest
 *       observations ({@link MacroSeriesChart})</li>
 * </ul>
 */
@RestController
@RequiresPermission(Permission.COMPANIES)
@RequestMapping(path = "/api/v1/macro/data", produces = MediaType.APPLICATION_JSON_VALUE)
public class MacroDataController {

    /**
     * A series of the catalog with what is stored of it.
     *
     * @param state UP_TO_DATE, BEHIND, NEVER_FETCHED, FAILING or MANUAL (as on Ingestion › Macro)
     */
    public record Series(String code, String name, String country, String category, String unit, String frequency,
                         String source, String sourceUrl, String description, List<String> usedBy, int rows,
                         LocalDate firstDate, LocalDate lastDate, BigDecimal lastValue, LocalDate lastRelease,
                         String state, MacroSeriesChart.Kind kind) {

        static Series of(SeriesStatus s) {
            return new Series(s.code(), s.name(), s.country(), s.category(), s.unit(), s.frequency(), s.sourceLabel(),
                    s.sourceUrl(), s.description(), s.usedBy(), s.rows(), s.firstDate(), s.lastDate(), s.lastValue(),
                    s.lastRelease(), s.state(), MacroSeriesChart.kind(s.code(), s.unit()));
        }
    }

    public record SeriesList(LocalDate today, List<Series> series) {
    }

    public record SeriesChart(Series series, MacroSeriesChart.Chart chart) {
    }

    private final MacroService macro;
    private final MacroRepository repository;

    public MacroDataController(MacroService macro, MacroRepository repository) {
        this.macro = macro;
        this.repository = repository;
    }

    @GetMapping("/series")
    public SeriesList series() {
        MacroService.Status status = macro.status();
        return new SeriesList(status.today(), status.items().stream().map(Series::of).toList());
    }

    @GetMapping("/series/{code}")
    public SeriesChart chart(@PathVariable("code") String code,
                             @RequestParam(name = "range", defaultValue = "1Y") String range) {
        String r = MacroOutlookService.range(range); // an unknown range: 400 before any read
        SeriesStatus status = macro.seriesStatus(code); // 404 for an unknown code
        Series series = Series.of(status);
        MacroSeriesChart.Chart chart = MacroSeriesChart.of(repository.observations(status.code(), null), series.kind(),
                r);
        return new SeriesChart(series, chart);
    }

    @ExceptionHandler(SeriesNotFoundException.class)
    ProblemDetail notFound(SeriesNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Macro series not found");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }
}
