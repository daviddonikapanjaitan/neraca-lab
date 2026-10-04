package com.neracalab.backend.screening;

import java.util.Arrays;
import java.util.Locale;

/**
 * The investor perspectives a screening can apply ({@code screening_agent_score.agent}). Each has a
 * quantitative scorecard (Stage 1, {@code QuantScorer}) and an LLM persona (Stage 2,
 * {@code ScreeningPrompts}). {@link #RISK} scores safety: higher is safer.
 */
public enum InvestorAgent {

    BUFFETT("Warren Buffett", "Durable competitive advantage, high and consistent ROE, low debt, owner earnings at a sensible price"),
    MUNGER("Charlie Munger", "Wonderful businesses at fair prices: high returns on capital, pricing power, low capital intensity"),
    LYNCH("Peter Lynch", "Growth at a reasonable price: PEG below 1, steady earnings growth, understandable stories"),
    FISHER("Philip Fisher", "Long-term growth companies: sustained sales growth, improving margins, quality management"),
    GILL("Keith Gill (Roaring Kitty)", "Deep value with a catalyst: cheap on book and cash flow, beaten-down price, contrarian setup"),
    RISK("Risk Agent", "Downside protection: leverage, liquidity, solvency, earnings stability and volatility (higher = safer)");

    private final String label;
    private final String focus;

    InvestorAgent(String label, String focus) {
        this.label = label;
        this.focus = focus;
    }

    public String label() {
        return label;
    }

    /** One line on what the agent looks for. */
    public String focus() {
        return focus;
    }

    /** Case-insensitive; also accepts "KEITH_GILL" / "ROARING_KITTY". Null when unknown. */
    public static InvestorAgent parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "KEITH_GILL", "ROARING_KITTY", "KEITH_GILL_(ROARING_KITTY)" -> GILL;
            case "WARREN_BUFFETT", "WARREN_BUFFET" -> BUFFETT;
            case "CHARLIE_MUNGER" -> MUNGER;
            case "PETER_LYNCH" -> LYNCH;
            case "PHILIP_FISHER" -> FISHER;
            case "RISK_AGENT" -> RISK;
            default -> Arrays.stream(values()).filter(a -> a.name().equals(normalized)).findFirst().orElse(null);
        };
    }
}
