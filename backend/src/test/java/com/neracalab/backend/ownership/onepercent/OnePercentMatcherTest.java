package com.neracalab.backend.ownership.onepercent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ownership.onepercent.OnePercentMatcher.Candidate;
import com.neracalab.backend.ownership.onepercent.OnePercentMatcher.Kind;
import com.neracalab.backend.ownership.onepercent.OnePercentMatcher.Match;

/** Which investor of a month is which of the month before: the cases of KSEI's lists of February - September 2026. */
class OnePercentMatcherTest {

    private static Candidate c(String ticker, String name, long shares) {
        return new Candidate(ticker, OnePercentParser.holderKey(name), name, shares);
    }

    private static Match of(List<Match> matches, int current) {
        return matches.stream().filter(m -> m.current() == current).findFirst().orElse(null);
    }

    @Test
    void theSameNameOnceNormalizedIsTheSameInvestor() {
        List<Match> m = OnePercentMatcher.match(
                List.of(c("ABBA", "PT. Beyond Media", 1_592_831_618L), c("AADI", "IR. T. PERMADI RACHMAT", 271_164_118L),
                        c("AEGS", "UOB KAY HIAN PRIVATE LIMITED", 44_095_700L)),
                List.of(c("AADI", "T PERMADI RACHMAT", 271_164_118L), c("ABBA", "PT BEYOND MEDIA", 1_600_000_000L),
                        c("AEGS", "UOB KAY HIAN PTE LTD", 44_095_700L)));
        assertThat(m).containsExactlyInAnyOrder(new Match(0, 1, Kind.SAME), new Match(1, 0, Kind.SAME),
                new Match(2, 2, Kind.SAME));
    }

    @Test
    void aNameSpelledAlmostTheSameIsRenamedWhateverItsShares() {
        List<Match> m = OnePercentMatcher.match(
                List.of(c("AGRO", "DANA PENSIUN PERKEBUNAN", 600_703_375L)),
                List.of(c("AGRO", "DANA PENSUN PERKEBUNAN", 610_000_000L)));
        assertThat(m).containsExactly(new Match(0, 0, Kind.RENAMED));
    }

    @Test
    void theSameBlockUnderAnotherNameIsMovedOrRenamed() {
        List<Match> m = OnePercentMatcher.match(
                List.of(c("AMAG", "BANK PAN INDONESIA TBK, PT", 388_000_000L), c("ASPI", "Eris (A)", 22_000_000L),
                        c("AMAG", "PT ASURANSI X", 50_000_000L)),
                List.of(c("AMAG", "Panin Bank Tbk, PT", 388_000_000L), c("ASPI", "ERIS", 22_000_000L),
                        c("AMAG", "PT ASURANSI X", 45_000_000L)));
        assertThat(of(m, 0)).isEqualTo(new Match(0, 0, Kind.MOVED));
        // "ERIS A" holds every word of "ERIS": the same investor renamed
        assertThat(of(m, 1)).isEqualTo(new Match(1, 1, Kind.RENAMED));
        assertThat(of(m, 2)).isEqualTo(new Match(2, 2, Kind.SAME));
    }

    @Test
    void fundsOfOneManagerAndNumberedNamesAreNotMatchedByName() {
        List<Match> m = OnePercentMatcher.match(
                List.of(c("BAYU", "REKSA DANA SUCORINVEST MAXI FUND", 8_979_300L),
                        c("BAYU", "REKSA DANA PROTEKSI 1", 5_000_000L)),
                List.of(c("BAYU", "REKSA DANA SUCORINVEST EQUITY FUND", 9_500_000L),
                        c("BAYU", "REKSA DANA PROTEKSI 2", 6_000_000L)));
        assertThat(m).isEmpty();
    }

    @Test
    void anAmbiguousBlockIsNotMatched() {
        // two investors of the month before hold 100 shares: which one moved is unknown
        List<Match> m = OnePercentMatcher.match(
                List.of(c("ZZOP", "ALFA", 100), c("ZZOP", "BETA", 100)),
                List.of(c("ZZOP", "GAMMA", 100)));
        assertThat(m).isEmpty();
    }

    @Test
    void onlyTheSameCompanyIsCompared() {
        List<Match> m = OnePercentMatcher.match(List.of(c("AAAA", "PT ALFA", 100)), List.of(c("BBBB", "PT ALFA", 100)));
        assertThat(m).isEmpty();
    }

    @Test
    void namesAreComparedBySpelling() {
        assertThat(OnePercentMatcher.distance("PENSUN", "PENSIUN")).isEqualTo(1);
        assertThat(OnePercentMatcher.nameSimilarity("DANA PENSUN PERKEBUNAN", "DANA PENSIUN PERKEBUNAN"))
                .isGreaterThan(0.95);
        assertThat(OnePercentMatcher.nameSimilarity("TIS", "TISI")).isZero();
        assertThat(OnePercentMatcher.alike("ERIS A", "ERIS")).isTrue();
        assertThat(OnePercentMatcher.alike("BANK PAN INDONESIA", "PANIN BANK")).isFalse();
    }
}
