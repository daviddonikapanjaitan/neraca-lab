package com.neracalab.backend.market;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.market.WorldBankPinkSheetClient.MonthlyValue;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Monthly commodity prices of the IMF Primary Commodity Price System (PCPS), for what the World Bank Pink Sheet does
 * not have (cobalt): the SDMX 3.0 data API, no key, {@code /data/dataflow/IMF.RES/PCPS/+/G001.PCOBA.USD.M} - world
 * (G001), the commodity, its price in US dollars (USD), monthly (M). The answer is SDMX-JSON: one series whose
 * observations are indexes into the TIME_PERIOD values ("2026-M09"). Anything else - no series, a period that is not a
 * month, a price that is not positive - fails loudly; nothing is guessed.
 */
@Component
public class ImfPcpsClient {

    static final String SOURCE = "imf";

    /** "2026-M09" */
    private static final Pattern MONTH = Pattern.compile("(\\d{4})-M(\\d{2})");

    private final PoliteHttpClient http;
    private final MarketProperties properties;
    private final JsonMapper json;

    public ImfPcpsClient(PoliteHttpClient http, MarketProperties properties, JsonMapper json) {
        this.http = http;
        this.properties = properties;
        this.json = json;
    }

    /** Every month of the indicator {@code indicator} ("PCOBA") in US dollars, oldest first. */
    public List<MonthlyValue> monthly(String indicator) {
        URI uri = URI.create(properties.imf().baseUrl() + "/data/dataflow/IMF.RES/PCPS/+/G001."
                + URLEncoder.encode(indicator, StandardCharsets.UTF_8) + ".USD.M");
        return parse(http.getText(uri), indicator, json);
    }

    static List<MonthlyValue> parse(String body, String indicator, JsonMapper json) {
        JsonNode root;
        try {
            root = json.readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            throw new SourceException("IMF PCPS " + indicator + ": the answer is not JSON", e);
        }
        JsonNode periods = root.path("data").path("structures").path(0).path("dimensions").path("observation").path(0);
        if (!"TIME_PERIOD".equals(periods.path("id").asString(""))) {
            throw new SourceException("IMF PCPS " + indicator + ": no TIME_PERIOD dimension in the answer");
        }
        JsonNode series = root.path("data").path("dataSets").path(0).path("series");
        if (!series.isObject() || series.size() != 1) {
            throw new SourceException("IMF PCPS " + indicator + ": expected one series, found "
                    + (series.isObject() ? series.size() : 0));
        }
        JsonNode observations = series.iterator().next().path("observations");
        List<MonthlyValue> out = new ArrayList<>();
        for (var entry : observations.properties()) {
            int index = Integer.parseInt(entry.getKey());
            String period = periods.path("values").path(index).path("value").asString("");
            Matcher m = MONTH.matcher(period);
            if (!m.matches()) {
                throw new SourceException("IMF PCPS " + indicator + ": period '" + period + "' is not a month");
            }
            JsonNode value = entry.getValue().path(0);
            if (value.isNull() || value.isMissingNode() || value.asString("").isBlank()) {
                continue;   // no price that month
            }
            double v;
            try {
                v = Double.parseDouble(value.asString());
            } catch (NumberFormatException e) {
                throw new SourceException("IMF PCPS " + indicator + " of " + period + ": '" + value.asString() + "' is not a number");
            }
            if (v <= 0) {
                throw new SourceException("IMF PCPS " + indicator + " of " + period + " is " + v);
            }
            out.add(new MonthlyValue(LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1), v));
        }
        if (out.isEmpty()) {
            throw new SourceException("IMF PCPS " + indicator + " has no monthly value");
        }
        out.sort(java.util.Comparator.comparing(MonthlyValue::month));
        for (int i = 1; i < out.size(); i++) {
            if (out.get(i).month().equals(out.get(i - 1).month())) {
                throw new SourceException("IMF PCPS " + indicator + ": " + out.get(i).month() + " appears twice");
            }
        }
        return out;
    }
}
