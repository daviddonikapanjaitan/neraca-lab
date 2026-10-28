package com.neracalab.backend.ownership.onepercent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.neracalab.backend.ownership.onepercent.OnePercentRepository.MovementRow;

/**
 * What KSEI's monthly lists of the shareholders of more than 1% say about one company over every stored month: who
 * holds 1% or more in the latest month (by type: individuals, corporates, funds, financial institutions, government),
 * how their holdings moved month by month, who is new above 1% and who is no longer, and which names changed. No
 * model: read by the AI stock analysis (the dossier of every agent) and written as the text of the company's RAG
 * document.
 * <p>
 * Ownership is a fraction of the shares listed (0.4110 = 41.10%, as KSEI prints it), as in the 5% section. An
 * investor is followed through the months by its matches ({@link OnePercentMatcher}), also when its name changed.
 */
public final class OnePercentDigest {

    /** Most dated movements kept (newest first). */
    public static final int MAX_TRANSACTIONS = 40;

    /**
     * An investor of 1% or more in the latest month.
     *
     * @param otherNames     the other names of its rows that month
     * @param status         LOCAL, FOREIGN or null
     * @param ownership      fraction of the shares listed
     * @param scrip          of its shares, those held as scrip (certificates)
     * @param latestMovement BUY, SELL, UNCHANGED, ENTERED (new above 1%) or null (no list before it)
     * @param latestChange   shares bought (+) or sold (-) since the month before; null when it was new or no list
     *                       before is stored
     * @param previousName   its name the month before, when it was another (RENAMED or MOVED)
     * @param since          the first month of its stored holding (the first stored list, or the month it was new)
     * @param netChange      shares since then
     */
    public record HolderLine(String name, List<String> otherNames, String investorType, String typeGroup,
                             String status, String domicile, Long shares, Long scrip, BigDecimal ownership,
                             String latestMovement, Long latestChange, String previousName, String matchKind,
                             LocalDate since, Long sharesSince, Long netChange) {
    }

    /**
     * A dated movement: BUY, SELL, ENTERED (new above 1%), EXITED (no longer above 1%), or a name change of an
     * unchanged holding (RENAMED / MOVED).
     */
    public record Transaction(LocalDate date, String name, String movement, Long sharesBefore, Long sharesAfter,
                              Long change, BigDecimal ownershipBefore, BigDecimal ownershipAfter, String previousName,
                              String matchKind) {
    }

    /** The investors of one type group in the latest month. */
    public record GroupShare(String group, int holders, BigDecimal ownership) {
    }

    /**
     * @param holdersOwnership the investors of 1% or more together in the latest month
     * @param foreignOwnership of them the foreign ones (status A)
     * @param top3Ownership    the three largest together
     * @param renamed          movements of an investor under a name spelled otherwise (any month)
     * @param moved            blocks of shares listed under another name than the month before (any month)
     */
    public record Totals(int holders, BigDecimal holdersOwnership, BigDecimal localOwnership,
                         BigDecimal foreignOwnership, BigDecimal top3Ownership, int buys, int sells, int entered,
                         int exited, int renamed, int moved) {
    }

    /** An investor listed in an earlier month but not in the latest. */
    public record FormerHolder(String name, LocalDate lastListed, Long sharesThen, BigDecimal ownershipThen) {
    }

    /**
     * @param months        stored lists naming the company
     * @param byType        the latest month's holders by type group, largest first
     * @param formerHolders listed in an earlier month, not in the latest (latest listing first)
     */
    public record Digest(String ticker, String issuerName, LocalDate firstDate, LocalDate latestDate, int months,
                         String reading, Totals totals, List<GroupShare> byType, List<HolderLine> holders,
                         List<Transaction> transactions, List<FormerHolder> formerHolders) {
    }

    private OnePercentDigest() {
    }

    /**
     * The digest of a company's investors ({@link OnePercentRepository#holders}, latest month first); null when none
     * is stored.
     */
    public static Digest of(String ticker, List<MovementRow> rows) {
        List<MovementRow> listed = rows.stream().filter(r -> !r.leftList()).toList();
        if (listed.isEmpty()) {
            return null;
        }
        TreeSet<LocalDate> dates = new TreeSet<>();
        listed.forEach(r -> dates.add(r.reportDate()));
        LocalDate latest = dates.last();
        Map<Long, MovementRow> byId = new HashMap<>();
        listed.forEach(r -> byId.put(r.holderId(), r));
        String issuer = listed.getFirst().issuerName();

        List<HolderLine> holders = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal local = BigDecimal.ZERO;
        BigDecimal foreign = BigDecimal.ZERO;
        Map<String, int[]> groupCounts = new LinkedHashMap<>();
        Map<String, BigDecimal> groupShares = new LinkedHashMap<>();
        Set<String> latestKeys = new HashSet<>();
        for (MovementRow r : listed) {
            if (!r.reportDate().equals(latest)) {
                continue;
            }
            // back through its matches to the first month of the holding (its earlier names are not former holders);
            // matched with a list outside the rows (a date filter), it starts with that list's shares
            MovementRow start = r;
            latestKeys.add(r.holderKey());
            Set<Long> seen = new HashSet<>();
            while (start.previousHolderId() != null && byId.containsKey(start.previousHolderId())
                    && seen.add(start.holderId())) {
                start = byId.get(start.previousHolderId());
                latestKeys.add(start.holderKey());
            }
            LocalDate since = start.sharesPrevious() != null ? start.previousDate() : start.reportDate();
            Long sharesSince = start.sharesPrevious() != null ? start.sharesPrevious() : start.sharesCurrent();
            Long net = sharesSince == null || r.sharesCurrent() == null ? null : r.sharesCurrent() - sharesSince;
            BigDecimal own = fraction(r.percentCurrent());
            String previousName = r.matchKind() != null && !"SAME".equals(r.matchKind()) ? r.previousName() : null;
            holders.add(new HolderLine(r.holderName(), r.otherNames(), r.investorType(), r.typeGroup(),
                    status(r.status()), r.domicile(), r.sharesCurrent(), r.sharesScrip(), own, r.movement(),
                    r.change(), previousName, r.matchKind(), since, sharesSince, net));
            String group = r.typeGroup() == null ? "UNKNOWN" : r.typeGroup();
            groupCounts.computeIfAbsent(group, k -> new int[1])[0]++;
            if (own != null) {
                total = total.add(own);
                groupShares.merge(group, own, BigDecimal::add);
                if ("A".equals(r.status())) {
                    foreign = foreign.add(own);
                } else if ("L".equals(r.status())) {
                    local = local.add(own);
                }
            }
        }
        holders.sort(Comparator.comparing((HolderLine h) -> h.ownership() == null ? BigDecimal.ZERO : h.ownership())
                .reversed());
        BigDecimal top3 = holders.stream().limit(3).map(h -> h.ownership() == null ? BigDecimal.ZERO : h.ownership())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<GroupShare> byType = new ArrayList<>();
        groupCounts.forEach((g, n) -> byType.add(new GroupShare(g, n[0], groupShares.getOrDefault(g, BigDecimal.ZERO))));
        byType.sort(Comparator.comparing(GroupShare::ownership).reversed());

        List<Transaction> transactions = new ArrayList<>();
        int buys = 0;
        int sells = 0;
        int entered = 0;
        int exited = 0;
        int renamed = 0;
        int moved = 0;
        for (MovementRow r : rows) {
            if ("RENAMED".equals(r.matchKind())) {
                renamed++;
            } else if ("MOVED".equals(r.matchKind())) {
                moved++;
            }
            String movement = r.movement();
            if (movement == null) {
                continue;
            }
            boolean nameChange = r.matchKind() != null && !"SAME".equals(r.matchKind());
            switch (movement) {
                case "BUY" -> buys++;
                case "SELL" -> sells++;
                case "ENTERED" -> entered++;
                case "EXITED" -> exited++;
                default -> {
                    if (!nameChange) {
                        continue;
                    }
                    movement = r.matchKind();
                }
            }
            transactions.add(new Transaction(r.reportDate(), r.holderName(), movement, r.sharesPrevious(),
                    r.sharesCurrent(), r.change(), fraction(r.percentPrevious()), fraction(r.percentCurrent()),
                    nameChange ? r.previousName() : null, r.matchKind()));
        }
        transactions.sort(Comparator.comparing(Transaction::date).reversed());
        if (transactions.size() > MAX_TRANSACTIONS) {
            transactions = new ArrayList<>(transactions.subList(0, MAX_TRANSACTIONS));
        }

        // no longer listed: the latest exit of each investor that is not listed in the latest month
        List<FormerHolder> former = new ArrayList<>();
        Set<String> formerKeys = new HashSet<>();
        rows.stream().filter(MovementRow::leftList)
                .sorted(Comparator.comparing(MovementRow::reportDate).reversed())
                .forEach(r -> {
                    if (!latestKeys.contains(r.holderKey()) && formerKeys.add(r.holderKey())) {
                        former.add(new FormerHolder(r.holderName(), r.previousDate(), r.sharesPrevious(),
                                fraction(r.percentPrevious())));
                    }
                });

        Totals totals = new Totals(holders.size(), total, local, foreign, top3, buys, sells, entered, exited, renamed,
                moved);
        Digest digest = new Digest(ticker, issuer, dates.first(), latest, dates.size(), null, totals,
                List.copyOf(byType), List.copyOf(holders), List.copyOf(transactions), List.copyOf(former));
        return new Digest(ticker, issuer, digest.firstDate(), latest, dates.size(), reading(digest), totals,
                digest.byType(), digest.holders(), digest.transactions(), digest.formerHolders());
    }

    /** One line: who holds the company and what moved. */
    static String reading(Digest d) {
        Totals t = d.totals();
        StringBuilder b = new StringBuilder();
        b.append("On ").append(d.latestDate()).append(", ").append(t.holders())
                .append(t.holders() == 1 ? " investor held" : " investors held").append(" more than 1% of ")
                .append(d.ticker()).append(" (together ").append(pct(t.holdersOwnership())).append(" of the shares listed");
        if (t.foreignOwnership().signum() > 0) {
            b.append(", foreign ").append(pct(t.foreignOwnership()));
        }
        b.append(")");
        if (!d.holders().isEmpty()) {
            HolderLine top = d.holders().getFirst();
            b.append("; the largest is ").append(top.name()).append(" with ").append(pct(top.ownership()));
        }
        b.append(". ");
        if (d.months() == 1) {
            b.append("One monthly list is stored (").append(d.latestDate()).append(")");
        } else {
            b.append(d.months()).append(" monthly lists stored (").append(d.firstDate()).append(" to ")
                    .append(d.latestDate()).append(")");
        }
        if (t.buys() + t.sells() + t.entered() + t.exited() == 0) {
            b.append(d.months() == 1 ? "." : ": no investor of 1% or more bought or sold.");
        } else {
            List<String> parts = new ArrayList<>();
            if (t.buys() > 0) {
                parts.add(t.buys() + (t.buys() == 1 ? " buy" : " buys"));
            }
            if (t.sells() > 0) {
                parts.add(t.sells() + (t.sells() == 1 ? " sell" : " sells"));
            }
            if (t.entered() > 0) {
                parts.add(t.entered() + " new above 1%");
            }
            if (t.exited() > 0) {
                parts.add(t.exited() + " no longer above 1%");
            }
            b.append(": ").append(String.join(", ", parts)).append(".");
        }
        return b.toString();
    }

    /**
     * The digest as the text of the company's RAG document (English, plain lines): the latest holders with their
     * types, the totals and the types, every dated movement, the former holders.
     */
    public static String text(Digest d) {
        StringBuilder b = new StringBuilder();
        b.append(d.ticker()).append(" - ").append(d.issuerName())
                .append(": shareholders of more than 1% (KSEI monthly list of shareholders above 1%, published on "
                        + "idx.co.id).\n");
        b.append(d.reading()).append("\n\n");
        b.append("Shareholders of more than 1% on ").append(d.latestDate()).append(":\n");
        int i = 1;
        for (HolderLine h : d.holders()) {
            b.append(i++).append(". ").append(h.name());
            if (!h.otherNames().isEmpty()) {
                b.append(" (also listed as ").append(String.join("; ", h.otherNames())).append(")");
            }
            b.append(", ").append(h.investorType() == null ? "type not given" : h.investorType().toLowerCase(Locale.ROOT))
                    .append(", ").append(h.status() == null ? "status not given" : h.status().toLowerCase(Locale.ROOT));
            if (h.domicile() != null) {
                b.append(", domicile ").append(h.domicile());
            }
            b.append(": ").append(shares(h.shares())).append(" shares, ").append(pct(h.ownership()))
                    .append(" of the shares listed");
            if (h.scrip() != null && h.scrip() > 0) {
                b.append(" (").append(shares(h.scrip())).append(" as scrip)");
            }
            if (h.previousName() != null) {
                b.append("; listed as ").append(h.previousName()).append(" the month before");
            }
            if (h.netChange() != null && h.netChange() != 0 && !h.since().equals(d.latestDate())) {
                b.append("; ").append(h.netChange() > 0 ? "bought " : "sold ").append(shares(Math.abs(h.netChange())))
                        .append(" shares since ").append(h.since());
            } else if (h.netChange() != null && !h.since().equals(d.latestDate())) {
                b.append("; unchanged since ").append(h.since());
            } else if ("ENTERED".equals(h.latestMovement())) {
                b.append("; new above 1% this month");
            }
            b.append(".\n");
        }
        Totals t = d.totals();
        b.append("Together ").append(pct(t.holdersOwnership())).append(" (local ").append(pct(t.localOwnership()))
                .append(", foreign ").append(pct(t.foreignOwnership())).append("; the three largest ")
                .append(pct(t.top3Ownership())).append(").\n");
        if (!d.byType().isEmpty()) {
            List<String> types = new ArrayList<>();
            for (GroupShare g : d.byType()) {
                types.add(g.group().toLowerCase(Locale.ROOT) + " " + pct(g.ownership()) + " (" + g.holders()
                        + (g.holders() == 1 ? " investor)" : " investors)"));
            }
            b.append("By type: ").append(String.join(", ", types)).append(".\n");
        }
        if (!d.transactions().isEmpty()) {
            b.append("\nMonthly movements of the shareholders of 1% or more (newest first):\n");
            for (Transaction x : d.transactions()) {
                b.append(x.date()).append(": ").append(x.name()).append(' ');
                switch (x.movement()) {
                    case "BUY", "SELL" -> b.append(x.change() > 0 ? "bought " : "sold ")
                            .append(shares(Math.abs(x.change()))).append(" shares (").append(shares(x.sharesBefore()))
                            .append(" -> ").append(shares(x.sharesAfter())).append(", ")
                            .append(pct(x.ownershipBefore())).append(" -> ").append(pct(x.ownershipAfter()))
                            .append(")");
                    case "ENTERED" -> b.append("new above 1% with ").append(shares(x.sharesAfter())).append(" shares (")
                            .append(pct(x.ownershipAfter())).append(")");
                    case "EXITED" -> b.append("no longer above 1% (held ").append(shares(x.sharesBefore()))
                            .append(" shares, ").append(pct(x.ownershipBefore())).append(", the month before)");
                    case "MOVED" -> b.append("holds the same ").append(shares(x.sharesAfter()))
                            .append(" shares listed under ").append(x.previousName()).append(" the month before");
                    default -> b.append("is the same holding listed as ").append(x.previousName())
                            .append(" the month before (").append(shares(x.sharesAfter())).append(" shares)");
                }
                if (x.previousName() != null && ("BUY".equals(x.movement()) || "SELL".equals(x.movement()))) {
                    b.append(", listed as ").append(x.previousName()).append(" the month before");
                }
                b.append(".\n");
            }
        }
        if (!d.formerHolders().isEmpty()) {
            b.append("\nNo longer listed (below 1%): ");
            List<String> names = new ArrayList<>();
            for (FormerHolder f : d.formerHolders()) {
                names.add(f.name() + " (last listed " + f.lastListed() + " with " + shares(f.sharesThen()) + " shares, "
                        + pct(f.ownershipThen()) + ")");
            }
            b.append(String.join("; ", names)).append(".\n");
        }
        return b.toString();
    }

    /** 41.10 (percent) -> 0.4110 */
    private static BigDecimal fraction(BigDecimal percent) {
        return percent == null ? null : percent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
    }

    private static String status(String code) {
        return code == null ? null : "A".equals(code) ? "FOREIGN" : "LOCAL";
    }

    /** 0.4110 -> "41.10%" */
    static String pct(BigDecimal fraction) {
        return fraction == null ? "n/a" : fraction.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String shares(Long value) {
        return value == null ? "n/a" : String.format(Locale.ROOT, "%,d", value);
    }
}
