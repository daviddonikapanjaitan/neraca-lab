package com.neracalab.backend.portfolio.intraday;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The IDX trading day (Asia/Jakarta), as IDX publishes its sessions:
 * <ul>
 *   <li>pre-opening 08:45 - 09:00;</li>
 *   <li>session 1: 09:00 - 12:00, Friday 09:00 - 11:30;</li>
 *   <li>break: until 13:30, Friday until 14:00;</li>
 *   <li>session 2: 13:30 - 15:50, Friday 14:00 - 15:50;</li>
 *   <li>pre-closing 15:50 - 16:01 (the closing price is formed), post-trading 16:01 - 16:15 (trades at the close).</li>
 * </ul>
 * Weekends are closed. Exchange holidays are not known here: on one, Yahoo simply has no bar of the day, and the page
 * shows the latest session it has.
 */
public final class IdxSession {

    public static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");

    /** Where the trading day stands. */
    public enum Phase {
        PRE_OPENING("Pre-opening", false),
        SESSION_1("Session 1", true),
        BREAK("Lunch break", false),
        SESSION_2("Session 2", true),
        PRE_CLOSING("Pre-closing", true),
        POST_TRADING("Post-trading", true),
        CLOSED("Closed", false);

        private final String label;
        private final boolean trading;

        Phase(String label, boolean trading) {
            this.label = label;
            this.trading = trading;
        }

        public String label() {
            return label;
        }

        /** Prices can change in this phase. */
        public boolean trading() {
            return trading;
        }
    }

    private static final LocalTime PRE_OPENING = LocalTime.of(8, 45);
    private static final LocalTime OPEN = LocalTime.of(9, 0);
    private static final LocalTime PRE_CLOSING = LocalTime.of(15, 50);
    private static final LocalTime POST_TRADING = LocalTime.of(16, 1);
    private static final LocalTime CLOSE = LocalTime.of(16, 15);

    private IdxSession() {
    }

    public static Phase phase(ZonedDateTime time) {
        ZonedDateTime t = time.withZoneSameInstant(ZONE);
        DayOfWeek day = t.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return Phase.CLOSED;
        }
        boolean friday = day == DayOfWeek.FRIDAY;
        LocalTime breakStart = friday ? LocalTime.of(11, 30) : LocalTime.of(12, 0);
        LocalTime breakEnd = friday ? LocalTime.of(14, 0) : LocalTime.of(13, 30);
        LocalTime now = t.toLocalTime();
        if (now.isBefore(PRE_OPENING)) {
            return Phase.CLOSED;
        }
        if (now.isBefore(OPEN)) {
            return Phase.PRE_OPENING;
        }
        if (now.isBefore(breakStart)) {
            return Phase.SESSION_1;
        }
        if (now.isBefore(breakEnd)) {
            return Phase.BREAK;
        }
        if (now.isBefore(PRE_CLOSING)) {
            return Phase.SESSION_2;
        }
        if (now.isBefore(POST_TRADING)) {
            return Phase.PRE_CLOSING;
        }
        if (now.isBefore(CLOSE)) {
            return Phase.POST_TRADING;
        }
        return Phase.CLOSED;
    }
}
