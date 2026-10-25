package com.neracalab.backend.technical.screening;

import java.util.Arrays;
import java.util.Locale;

/**
 * The technical agents of a technical screening ({@code technical_screening_agent_score.agent}). Each reads the chart in
 * the style of its preset on Companies > Technicals ({@code PresetSummaries}): the preset's rules summary is its prior
 * (Stage 1), the agent's model call its judgement (Stage 2).
 */
public enum TechnicalAgent {

    GILL("gill", "Keith Gill (Roaring Kitty)",
            "Patient, contrarian accumulation: a base with contracting volatility near the lows, buyers returning on volume"),
    MURPHY("murphy", "John Murphy",
            "Trend following with confirmation: the primary trend, moving averages, MACD, RSI, volume and relative strength"),
    TUDOR_JONES("ptj", "Paul Tudor Jones",
            "The 200-day rule and breakouts with strict risk: ADX, 20 / 50-day channels, ATR stops, 5:1 reward to risk"),
    DRUCKENMILLER("druckenmiller", "Stanley Druckenmiller",
            "Leadership and the market: relative strength vs IHSG, 12-1 month momentum, the market's trend and breadth");

    private final String preset;
    private final String label;
    private final String focus;

    TechnicalAgent(String preset, String label, String focus) {
        this.preset = preset;
        this.label = label;
        this.focus = focus;
    }

    /** The preset of Companies > Technicals whose rules summary is the agent's prior: murphy, gill, ptj, druckenmiller. */
    public String preset() {
        return preset;
    }

    public String label() {
        return label;
    }

    /** One line on what the agent reads. */
    public String focus() {
        return focus;
    }

    /** Case-insensitive name, preset id or label ("KEITH_GILL", "ptj", "Paul Tudor Jones"); null when unknown. */
    public static TechnicalAgent parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "KEITH_GILL", "ROARING_KITTY", "KEITH_GILL_(ROARING_KITTY)" -> GILL;
            case "JOHN_MURPHY" -> MURPHY;
            case "PTJ", "PAUL_TUDOR_JONES", "TUDOR" -> TUDOR_JONES;
            case "STANLEY_DRUCKENMILLER" -> DRUCKENMILLER;
            default -> Arrays.stream(values()).filter(a -> a.name().equals(normalized)).findFirst().orElse(null);
        };
    }
}
