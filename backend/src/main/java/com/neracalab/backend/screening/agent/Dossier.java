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
 * <p>
 * Yahoo reports the fiscal years in the currency of the company's statements; those of a company that reports
 * in another currency than IDR are converted with {@code StockSnapshot.fundamentalsFx} (one rate for every year,
 * so growth rates stay those of the statements) and, without a stored rate, given as {@code annualBillions} with
 * their currency.
 */
public final class Dossier {

    private static final List<String> ANNUAL_KEYS = List.of("revenue", "netIncome", "operatingIncome",
            "freeCashFlow", "totalDebt", "equity", "totalAssets");

    private Dossier() {
    }

    /**
     * @param flags tradability checks the stock failed but was kept for (screening of selected stocks, e.g. low
     *              liquidity); empty otherwise
     */
    public static String of(JsonReplies json, Candidate c, MarketCapTier tier, List<String> flags, NewsBrief brief) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ticker", c.ticker());
        d.put("company", c.snapshot().companyName());
        d.put("sector", c.snapshot().sector());
        d.put("industry", c.snapshot().industry());
        d.put("tier", tier.label());
        if (!flags.isEmpty()) {
            d.put("tradabilityWarnings", flags);
        }
        d.put("dataDate", c.snapshot().snapshotDate() == null ? null : c.snapshot().snapshotDate().toString());
        d.put("metrics", c.profile().asMap());
        AnnualFigures annual = c.snapshot().fundamentals() == null ? null : c.snapshot().fundamentals().annual();
        if (annual != null && !annual.years().isEmpty()) {
            // a company reporting in another currency (INDY: USD): every year at the rate of the snapshot
            Double fx = c.snapshot().fundamentalsFx();
            boolean listingCurrency = fx != null && fx > 0;
            double rate = listingCurrency ? fx : 1;
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("fiscalYearEnds", annual.years());
            for (String key : ANNUAL_KEYS) {
                List<Double> series = annual.series(key);
                if (series.stream().anyMatch(v -> v != null)) {
                    List<Double> billions = new ArrayList<>();
                    for (Double v : series) {
                        billions.add(v == null ? null : Math.round(v * rate / 1e8) / 10.0);
                    }
                    a.put(key, billions);
                }
            }
            String currency = c.snapshot().fundamentalsCurrency();
            if (!listingCurrency) {
                // no rate stored: the amounts stay as reported, and are not IDR
                d.put("annualBillions", a);
                d.put("annualCurrency", currency);
            } else {
                d.put("annualIdrBillions", a);
                if (rate != 1) {
                    d.put("annualNote", "reported in " + currency + ", every year converted at " + Math.round(rate)
                            + " IDR per " + currency);
                }
            }
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
