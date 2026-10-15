package com.neracalab.backend.market;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Forex and commodity prices ({@code neracalab.markets.*}).
 *
 * @param worldBank the World Bank Pink Sheet (monthly commodity prices; coal)
 */
@ConfigurationProperties("neracalab.markets")
public record MarketProperties(@DefaultValue WorldBank worldBank) {

    /**
     * @param pageUrl the Commodity Markets page; it links the current CMO-Historical-Data-Monthly.xlsx (the file's address
     *                changes with every monthly release)
     * @param fileUrl the workbook itself; empty = the link found on {@code pageUrl}
     * @param sheet   the sheet of the monthly prices
     */
    public record WorldBank(@DefaultValue("https://www.worldbank.org/en/research/commodity-markets") String pageUrl,
                            @DefaultValue("") String fileUrl,
                            @DefaultValue("Monthly Prices") String sheet) {
    }
}
