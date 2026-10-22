package com.neracalab.backend.macro.analysis;

import java.util.Locale;

/**
 * The four macro agents of the AI macro analysis: the investors whose way of reading markets the Macro page's presets
 * already follow ({@code MacroOutlook}). Each agent's prior is its preset of the rules.
 */
public enum MacroAgent {

    TUDOR_JONES("Paul Tudor Jones", "ptj",
            "Defence first: the risk regime, volatility, credit, inflation, and nothing below the 200-day average"),
    DRUCKENMILLER("Stanley Druckenmiller", "druckenmiller",
            "Liquidity first: central banks, US net liquidity, the dollar and foreign money, 12 - 24 months ahead"),
    SOROS("George Soros", "soros",
            "Reflexivity: the loop of foreign money, the rupiah and Bank Indonesia that feeds a boom or a bust"),
    DALIO("Ray Dalio", "dalio",
            "The economic machine: the growth / inflation quadrant, real rates, credit and commodities");

    private final String label;
    private final String preset;
    private final String focus;

    MacroAgent(String label, String preset, String focus) {
        this.label = label;
        this.preset = preset;
        this.focus = focus;
    }

    /** "Paul Tudor Jones" */
    public String label() {
        return label;
    }

    /** The id of the agent's preset on the Macro page ({@code presets[].preset}): ptj, druckenmiller, soros, dalio. */
    public String preset() {
        return preset;
    }

    public String focus() {
        return focus;
    }

    /**
     * The agent of a name: its code (TUDOR_JONES), its preset id (ptj), its label or surname ("Paul Tudor Jones",
     * "Tudor Jones", "Soros"), case-insensitively; null when unknown.
     */
    public static MacroAgent parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (MacroAgent a : values()) {
            String label = a.label.toUpperCase(Locale.ROOT).replace(' ', '_');
            if (a.name().equals(n) || a.preset.toUpperCase(Locale.ROOT).equals(n) || label.equals(n)
                    || label.endsWith("_" + n)) {
                return a;
            }
        }
        return null;
    }
}
