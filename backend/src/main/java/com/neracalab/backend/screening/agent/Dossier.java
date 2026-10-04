package com.neracalab.backend.screening.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neracalab.backend.screening.MarketCapTier;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.AnnualFigures;
import com.neracalab.backend.screening.quant.QuantScreener.Candidate;

/**
 * The stock data every investor agent sees, as one compact JSON message. It is identical for all
 * agents of a stock (so the provider can cache it): metrics, four fiscal years in IDR billions and
 * the news brief. Agent-specific content (persona, scorecard) follows in a later message.
 */
public final class Dossier {

    private static final List<String> ANNUAL_KEYS = List.of("revenue", "netIncome", "operatingIncome",
            "freeCashFlow", "totalDebt", "equity", "totalAssets");

    private Dossier() {
    }

    public static String of(JsonReplies json, Candidate c, MarketCapTier tier, NewsBrief brief) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ticker", c.ticker());
        d.put("company", c.snapshot().companyName());
        d.put("sector", c.snapshot().sector());
        d.put("industry", c.snapshot().industry());
        d.put("tier", tier.label());
        d.put("dataDate", c.snapshot().snapshotDate() == null ? null : c.snapshot().snapshotDate().toString());
        d.put("metrics", c.profile().asMap());
        AnnualFigures annual = c.snapshot().fundamentals() == null ? null : c.snapshot().fundamentals().annual();
        if (annual != null && !annual.years().isEmpty()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("fiscalYearEnds", annual.years());
            for (String key : ANNUAL_KEYS) {
                List<Double> series = annual.series(key);
                if (series.stream().anyMatch(v -> v != null)) {
                    List<Double> billions = new ArrayList<>();
                    for (Double v : series) {
                        billions.add(v == null ? null : Math.round(v / 1e8) / 10.0);
                    }
                    a.put(key, billions);
                }
            }
            d.put("annualIdrBillions", a);
        }
        if (brief != null) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("sentiment", brief.sentiment());
            n.put("summary", brief.summary());
            n.put("catalysts", brief.catalysts());
            n.put("risks", brief.risks());
            d.put("news", n);
        }
        return "STOCK DATA\n" + json.write(d);
    }
}
