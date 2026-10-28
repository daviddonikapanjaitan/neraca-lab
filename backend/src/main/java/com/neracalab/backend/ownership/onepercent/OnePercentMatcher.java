package com.neracalab.backend.ownership.onepercent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which investor of a month's list is which investor of the list before it, company by company. KSEI's monthly lists
 * have no SID and spell names differently from month to month ("PT. Beyond Media" / "PT BEYOND MEDIA", "IR. T. PERMADI
 * RACHMAT" / "T PERMADI RACHMAT", "DANA PENSUN PERKEBUNAN" / "DANA PENSIUN PERKEBUNAN", "BANK PAN INDONESIA TBK, PT" /
 * "Panin Bank Tbk, PT"), so an investor is matched, in this order:
 * <ol>
 *   <li>{@link Kind#SAME}: the same normalized name ({@link OnePercentParser#holderKey}), shares changed or not;</li>
 *   <li>{@link Kind#RENAMED}: a name spelled almost the same (at least 90% alike, the same digits, both at least six
 *       characters), the best and only such pair of the two investors, shares changed or not;</li>
 *   <li>the exact same number of shares, the only investor left with it on both sides: {@link Kind#RENAMED} when the
 *       names are alike (70%, or one's words all in the other's), else {@link Kind#MOVED}: the same block of shares
 *       under another name (a new name, a transfer between related holders, a custodian change).</li>
 * </ol>
 * An investor of the list matched with none of the month before is new above 1%; one of the month before matched
 * with none of the month is no longer above 1% (sold, or diluted below it). Mutual funds of one manager differ by a
 * word ("REKSA DANA SUCORINVEST MAXI FUND" / "... EQUITY FUND"): they are not alike enough to be matched by name.
 */
public final class OnePercentMatcher {

    /** How an investor was matched with one of the list before. */
    public enum Kind {
        /** the same normalized name */
        SAME,
        /** the name spelled otherwise */
        RENAMED,
        /** the same block of shares under another name */
        MOVED
    }

    /** An investor of a company in a list. */
    public record Candidate(String ticker, String holderKey, String name, long shares) {
    }

    /** The investor of the list before ({@code previous} index) that investor {@code current} (index) is. */
    public record Match(int current, int previous, Kind kind) {
    }

    /** Names at least this alike are the same investor whatever its shares. */
    static final double RENAMED_SIMILARITY = 0.9;
    /** Names at least this alike with the same shares are the same investor renamed (else a moved block). */
    static final double ALIKE_SIMILARITY = 0.7;
    /** Shortest normalized name matched by its spelling. */
    static final int MIN_FUZZY_LENGTH = 6;

    private OnePercentMatcher() {
    }

    /** The matches of {@code current} (a list) with {@code previous} (the list before it), any order. */
    public static List<Match> match(List<Candidate> previous, List<Candidate> current) {
        Map<String, List<Integer>> prevByTicker = byTicker(previous);
        Map<String, List<Integer>> currByTicker = byTicker(current);
        List<Match> out = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : currByTicker.entrySet()) {
            List<Integer> prev = prevByTicker.getOrDefault(e.getKey(), List.of());
            if (!prev.isEmpty()) {
                out.addAll(matchCompany(previous, prev, current, e.getValue()));
            }
        }
        return out;
    }

    private static Map<String, List<Integer>> byTicker(List<Candidate> list) {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            out.computeIfAbsent(list.get(i).ticker(), k -> new ArrayList<>()).add(i);
        }
        return out;
    }

    private static List<Match> matchCompany(List<Candidate> previous, List<Integer> prev, List<Candidate> current,
                                            List<Integer> curr) {
        List<Match> out = new ArrayList<>();
        Set<Integer> prevLeft = new HashSet<>(prev);
        Set<Integer> currLeft = new HashSet<>(curr);

        // 1. the same normalized name (the first of the list before with it)
        Map<String, Integer> prevByKey = new HashMap<>();
        for (int p : prev) {
            prevByKey.putIfAbsent(previous.get(p).holderKey(), p);
        }
        for (int c : curr) {
            Integer p = prevByKey.get(current.get(c).holderKey());
            if (p != null && prevLeft.contains(p)) {
                out.add(new Match(c, p, Kind.SAME));
                prevLeft.remove(p);
                currLeft.remove(c);
            }
        }

        // 2. a name spelled almost the same: the most alike pairs first; a pair as alike as another pair of one of
        //    its investors is ambiguous and left
        record Pair(int c, int p, double s) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (int c : currLeft) {
            for (int p : prevLeft) {
                double s = nameSimilarity(current.get(c).holderKey(), previous.get(p).holderKey());
                if (s >= RENAMED_SIMILARITY) {
                    pairs.add(new Pair(c, p, s));
                }
            }
        }
        pairs.sort((x, y) -> Double.compare(y.s(), x.s()));
        for (Pair pair : pairs) {
            if (!currLeft.contains(pair.c()) || !prevLeft.contains(pair.p())) {
                continue;
            }
            boolean tied = pairs.stream().anyMatch(o -> o != pair && o.s() == pair.s()
                    && (o.c() == pair.c() && prevLeft.contains(o.p()) || o.p() == pair.p() && currLeft.contains(o.c())));
            if (!tied) {
                out.add(new Match(pair.c(), pair.p(), Kind.RENAMED));
                prevLeft.remove(pair.p());
                currLeft.remove(pair.c());
            }
        }

        // 3. the same block of shares, the only one with that number on both sides
        Map<Long, List<Integer>> prevByShares = new HashMap<>();
        for (int p : prevLeft) {
            prevByShares.computeIfAbsent(previous.get(p).shares(), k -> new ArrayList<>()).add(p);
        }
        Map<Long, List<Integer>> currByShares = new HashMap<>();
        for (int c : currLeft) {
            currByShares.computeIfAbsent(current.get(c).shares(), k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<Long, List<Integer>> e : currByShares.entrySet()) {
            List<Integer> same = prevByShares.get(e.getKey());
            if (e.getKey() > 0 && e.getValue().size() == 1 && same != null && same.size() == 1) {
                int c = e.getValue().getFirst();
                int p = same.getFirst();
                Kind kind = alike(current.get(c).holderKey(), previous.get(p).holderKey()) ? Kind.RENAMED : Kind.MOVED;
                out.add(new Match(c, p, kind));
            }
        }
        return out;
    }

    /**
     * How alike two normalized names are by their spelling (1 - edit distance / longer length); 0 when either is
     * shorter than {@link #MIN_FUZZY_LENGTH} or their digits differ ("REKSA DANA X 1" is not "REKSA DANA X 2").
     */
    static double nameSimilarity(String a, String b) {
        if (a.length() < MIN_FUZZY_LENGTH || b.length() < MIN_FUZZY_LENGTH) {
            return 0;
        }
        if (!a.replaceAll("\\D", "").equals(b.replaceAll("\\D", ""))) {
            return 0;
        }
        return similarity(a, b);
    }

    /** Names alike enough for one block of shares to be the same investor renamed. */
    static boolean alike(String a, String b) {
        if (similarity(a, b) >= ALIKE_SIMILARITY) {
            return true;
        }
        Set<String> wa = new HashSet<>(List.of(a.split(" ")));
        Set<String> wb = new HashSet<>(List.of(b.split(" ")));
        return wa.containsAll(wb) || wb.containsAll(wa);
    }

    static double similarity(String a, String b) {
        int longer = Math.max(a.length(), b.length());
        return longer == 0 ? 1 : 1 - (double) distance(a, b) / longer;
    }

    /** Levenshtein edit distance. */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
