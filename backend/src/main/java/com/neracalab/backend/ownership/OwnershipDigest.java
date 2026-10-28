package com.neracalab.backend.ownership;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import com.neracalab.backend.ownership.OwnershipRepository.AccountRow;
import com.neracalab.backend.ownership.OwnershipRepository.HolderDay;
import com.neracalab.backend.ownership.OwnershipRepository.MovementRow;

/**
 * What KSEI's daily lists of the shareholders of more than 5% say about one company over every stored day: who holds
 * 5% or more on the latest day, how their holdings moved, who crossed 5% and who fell below it. No model: read by the
 * AI stock analysis (the dossier of every agent) and written as the text of the company's RAG document.
 * <p>
 * Ownership is a fraction of the shares listed (0.4110 = 41.10%, as KSEI prints it), as in the insider section.
 * An investor listed again under another name with the same accounts (an alias) is counted once.
 */
public final class OwnershipDigest {

    /** Most dated movements kept (newest first). */
    public static final int MAX_TRANSACTIONS = 40;

    /**
     * An investor of 5% or more on the latest day.
     *
     * @param aliases        the other names it is listed under
     * @param status         LOCAL, FOREIGN or null
     * @param ownership      fraction of the shares listed
     * @param latestChange   shares bought (+) or sold (-) on the latest day; null when it entered that day
     * @param since          the first date of its stored holding (the day before its first stored list, or the day it
     *                       crossed 5%)
     * @param netChange      shares since then; null when it left the list and came back in between
     * @param participants   the brokers and custodians of its accounts
     */
    public record HolderLine(String name, List<String> aliases, String status, String domicile, Long shares,
                             BigDecimal ownership, int accounts, Long latestChange, LocalDate since,
                             Long sharesSince, Long netChange, List<String> participants) {
    }

    /** A dated movement: BUY, SELL, ENTERED (crossed 5%) or EXITED (fell below 5%). */
    public record Transaction(LocalDate date, String name, String movement, Long sharesBefore, Long sharesAfter,
                              Long change, BigDecimal ownershipBefore, BigDecimal ownershipAfter) {
    }

    /**
     * @param holdersOwnership the investors of 5% or more together on the latest day (aliases once)
     * @param foreignOwnership of them the foreign ones (status A)
     */
    public record Totals(int holders, BigDecimal holdersOwnership, BigDecimal localOwnership,
                         BigDecimal foreignOwnership, int buys, int sells, int entered, int exited,
                         int accountMoveDays) {
    }

    /** An investor listed on an earlier day but not on the latest. */
    public record FormerHolder(String name, LocalDate lastListed, Long sharesThen, BigDecimal ownershipThen) {
    }

    /**
     * @param days          stored lists naming the company
     * @param formerHolders listed on an earlier day, not on the latest (latest listing first)
     */
    public record Digest(String ticker, String issuerName, LocalDate firstDate, LocalDate latestDate, int days,
                         String reading, Totals totals, List<HolderLine> holders, List<Transaction> transactions,
                         List<FormerHolder> formerHolders) {
    }

    private OwnershipDigest() {
    }

    /**
     * The digest of a company's investors ({@link OwnershipRepository#holders}, latest day first); null when none is
     * stored.
     */
    public static Digest of(String ticker, List<HolderDay> days) {
        List<MovementRow> rows = days.stream().map(HolderDay::holder).filter(r -> r.aliasOf() == null).toList();
        if (rows.isEmpty()) {
            return null;
        }
        Map<Long, List<AccountRow>> accounts = new LinkedHashMap<>();
        days.forEach(d -> accounts.put(d.holder().holderId(), d.accounts()));
        TreeSet<LocalDate> dates = new TreeSet<>();
        rows.forEach(r -> dates.add(r.reportDate()));
        LocalDate latest = dates.last();
        String issuer = rows.getFirst().issuerName();

        // each investor's rows, oldest first
        Map<String, List<MovementRow>> byKey = new LinkedHashMap<>();
        rows.stream().sorted(Comparator.comparing(MovementRow::reportDate).thenComparing(MovementRow::leftList))
                .forEach(r -> byKey.computeIfAbsent(r.holderKey(), k -> new ArrayList<>()).add(r));

        List<HolderLine> holders = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal local = BigDecimal.ZERO;
        BigDecimal foreign = BigDecimal.ZERO;
        List<FormerHolder> former = new ArrayList<>();
        for (List<MovementRow> timeline : byKey.values()) {
            MovementRow last = timeline.getLast();
            MovementRow first = timeline.getFirst();
            boolean current = last.reportDate().equals(latest) && !last.leftList() && last.sharesCurrent() != null;
            if (!current) {
                MovementRow listed = timeline.stream().filter(r -> !r.leftList() && r.sharesCurrent() != null)
                        .reduce((a, b) -> b).orElse(null);
                if (listed != null) {
                    former.add(new FormerHolder(listed.holderName(), listed.reportDate(), listed.sharesCurrent(),
                            fraction(listed.percentCurrent())));
                }
                continue;
            }
            LocalDate since = first.sharesPrevious() != null ? first.previousDate() : first.reportDate();
            Long sharesSince = first.sharesPrevious() != null ? first.sharesPrevious() : first.sharesCurrent();
            boolean reentered = timeline.stream().skip(1).anyMatch(r -> "ENTERED".equals(r.movement()));
            Long net = reentered || sharesSince == null ? null : last.sharesCurrent() - sharesSince;
            List<String> participants = accounts.getOrDefault(last.holderId(), List.of()).stream()
                    .filter(a -> a.sharesCurrent() != null).map(AccountRow::participant).distinct().toList();
            BigDecimal own = fraction(last.percentCurrent());
            holders.add(new HolderLine(last.holderName(), last.aliases(), status(last.status()), last.domicile(),
                    last.sharesCurrent(), own, last.accounts(), last.change(), since, sharesSince, net, participants));
            if (own != null) {
                total = total.add(own);
                if ("A".equals(last.status())) {
                    foreign = foreign.add(own);
                } else if ("L".equals(last.status())) {
                    local = local.add(own);
                }
            }
        }
        holders.sort(Comparator.comparing((HolderLine h) -> h.ownership() == null ? BigDecimal.ZERO : h.ownership())
                .reversed());
        former.sort(Comparator.comparing(FormerHolder::lastListed).reversed());

        List<Transaction> transactions = new ArrayList<>();
        int buys = 0;
        int sells = 0;
        int entered = 0;
        int exited = 0;
        TreeSet<LocalDate> moveDays = new TreeSet<>();
        for (MovementRow r : rows) {
            switch (r.movement()) {
                case "BUY" -> buys++;
                case "SELL" -> sells++;
                case "ENTERED" -> entered++;
                case "EXITED" -> exited++;
                default -> {
                    if (r.accountMoves() > 0) {
                        moveDays.add(r.reportDate());
                    }
                    continue;
                }
            }
            transactions.add(new Transaction(r.reportDate(), r.holderName(), r.movement(), r.sharesPrevious(),
                    r.sharesCurrent(), r.change(), fraction(r.percentPrevious()), fraction(r.percentCurrent())));
        }
        transactions.sort(Comparator.comparing(Transaction::date).reversed());
        if (transactions.size() > MAX_TRANSACTIONS) {
            transactions = new ArrayList<>(transactions.subList(0, MAX_TRANSACTIONS));
        }
        Totals totals = new Totals(holders.size(), total, local, foreign, buys, sells, entered, exited,
                moveDays.size());
        Digest digest = new Digest(ticker, issuer, dates.first(), latest, dates.size(), null, totals,
                List.copyOf(holders), List.copyOf(transactions), List.copyOf(former));
        return new Digest(ticker, issuer, digest.firstDate(), latest, dates.size(), reading(digest), totals,
                digest.holders(), digest.transactions(), digest.formerHolders());
    }

    /** One line: who holds the company and what moved. */
    static String reading(Digest d) {
        Totals t = d.totals();
        StringBuilder b = new StringBuilder();
        b.append("On ").append(d.latestDate()).append(", ").append(t.holders())
                .append(t.holders() == 1 ? " investor held" : " investors held").append(" more than 5% of ")
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
        if (d.days() == 1) {
            b.append("One daily list is stored (").append(d.latestDate()).append(")");
        } else {
            b.append(d.days()).append(" daily lists stored (").append(d.firstDate()).append(" to ")
                    .append(d.latestDate()).append(")");
        }
        if (t.buys() + t.sells() + t.entered() + t.exited() == 0) {
            b.append(": no investor of 5% or more bought or sold.");
        } else {
            List<String> parts = new ArrayList<>();
            if (t.buys() > 0) {
                parts.add(t.buys() + (t.buys() == 1 ? " buy" : " buys"));
            }
            if (t.sells() > 0) {
                parts.add(t.sells() + (t.sells() == 1 ? " sell" : " sells"));
            }
            if (t.entered() > 0) {
                parts.add(t.entered() + " crossed 5%");
            }
            if (t.exited() > 0) {
                parts.add(t.exited() + " fell below 5%");
            }
            b.append(": ").append(String.join(", ", parts)).append(".");
        }
        return b.toString();
    }

    /**
     * The digest as the text of the company's RAG document (English, plain lines): the latest holders with their
     * accounts' brokers and custodians, the totals, every dated movement, the former holders.
     */
    public static String text(Digest d) {
        StringBuilder b = new StringBuilder();
        b.append(d.ticker()).append(" - ").append(d.issuerName())
                .append(": shareholders of more than 5% (KSEI daily list of securities ownership above 5% by investor "
                        + "SID, published on idx.co.id).\n");
        b.append(d.reading()).append("\n\n");
        b.append("Shareholders of more than 5% on ").append(d.latestDate()).append(":\n");
        int i = 1;
        for (HolderLine h : d.holders()) {
            b.append(i++).append(". ").append(h.name());
            if (!h.aliases().isEmpty()) {
                b.append(" (also listed as ").append(String.join("; ", h.aliases())).append(")");
            }
            b.append(", ").append(h.status() == null ? "status not given" : h.status().toLowerCase(Locale.ROOT));
            if (h.domicile() != null) {
                b.append(", domicile ").append(h.domicile());
            }
            b.append(": ").append(shares(h.shares())).append(" shares, ").append(pct(h.ownership()))
                    .append(" of the shares listed, in ").append(h.accounts())
                    .append(h.accounts() == 1 ? " account" : " accounts");
            if (!h.participants().isEmpty()) {
                b.append(" (").append(String.join(", ", h.participants())).append(")");
            }
            if (h.netChange() != null && h.netChange() != 0) {
                b.append("; ").append(h.netChange() > 0 ? "bought " : "sold ").append(shares(Math.abs(h.netChange())))
                        .append(" shares since ").append(h.since());
            } else if (h.netChange() != null) {
                b.append("; unchanged since ").append(h.since());
            }
            b.append(".\n");
        }
        Totals t = d.totals();
        b.append("Together ").append(pct(t.holdersOwnership())).append(" (local ").append(pct(t.localOwnership()))
                .append(", foreign ").append(pct(t.foreignOwnership())).append(").\n");
        if (!d.transactions().isEmpty()) {
            b.append("\nMovements of the shareholders of 5% or more (newest first):\n");
            for (Transaction x : d.transactions()) {
                b.append(x.date()).append(": ").append(x.name()).append(' ');
                switch (x.movement()) {
                    case "BUY", "SELL" -> b.append(x.change() > 0 ? "bought " : "sold ")
                            .append(shares(Math.abs(x.change()))).append(" shares (").append(shares(x.sharesBefore()))
                            .append(" -> ").append(shares(x.sharesAfter())).append(", ")
                            .append(pct(x.ownershipBefore())).append(" -> ").append(pct(x.ownershipAfter()))
                            .append(")");
                    case "ENTERED" -> b.append("crossed 5% with ").append(shares(x.sharesAfter())).append(" shares (")
                            .append(pct(x.ownershipAfter())).append(")");
                    default -> b.append("fell below 5% (held ").append(shares(x.sharesBefore())).append(" shares, ")
                            .append(pct(x.ownershipBefore())).append(", the day before)");
                }
                b.append(".\n");
            }
        }
        if (t.accountMoveDays() > 0) {
            b.append("\nOn ").append(t.accountMoveDays()).append(t.accountMoveDays() == 1 ? " day" : " days")
                    .append(" shares moved between the accounts of an investor without changing its total.\n");
        }
        if (!d.formerHolders().isEmpty()) {
            b.append("\nNo longer listed (below 5%): ");
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
        return percent == null ? null : percent.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
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
