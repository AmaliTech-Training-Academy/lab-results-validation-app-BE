package com.amalitech.labresultsvalidator.common.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpecializationNameMatcherTest {

    @Test
    void resolve_withExactNormalizedMatch_returnsMatched() {
        Map<String, String> candidates = Map.of(
            SpecializationNameMatcher.normalize("Software Engineering"), "Software Engineering");

        var result = SpecializationNameMatcher.resolve("  software   engineering ", candidates);

        assertThat(result.outcome()).isEqualTo(SpecializationNameMatcher.MatchOutcome.MATCHED);
        assertThat(result.value()).isEqualTo("Software Engineering");
    }

    @Test
    void resolve_withSingleSubstringCandidate_returnsMatched() {
        Map<String, String> candidates = Map.of(
            SpecializationNameMatcher.normalize("Software Engineering - Java"), "Software Engineering - Java");

        var result = SpecializationNameMatcher.resolve("Software Engineering", candidates);

        assertThat(result.outcome()).isEqualTo(SpecializationNameMatcher.MatchOutcome.MATCHED);
        assertThat(result.value()).isEqualTo("Software Engineering - Java");
    }

    @Test
    void resolve_withMultipleSubstringCandidates_returnsAmbiguous() {
        Map<String, String> candidates = Map.of(
            SpecializationNameMatcher.normalize("Software Engineering - Java"), "Software Engineering - Java",
            SpecializationNameMatcher.normalize("Software Engineering - JS"), "Software Engineering - JS");

        var result = SpecializationNameMatcher.resolve("Software Engineering", candidates);

        assertThat(result.outcome()).isEqualTo(SpecializationNameMatcher.MatchOutcome.AMBIGUOUS);
        assertThat(result.value()).isNull();
    }

    @Test
    void resolve_withExactMatchAmongAmbiguousCandidates_prefersExactMatch() {
        Map<String, String> candidates = Map.of(
            SpecializationNameMatcher.normalize("Software Engineering - Java"), "Software Engineering - Java",
            SpecializationNameMatcher.normalize("Software Engineering - JS"), "Software Engineering - JS");

        var result = SpecializationNameMatcher.resolve("Software Engineering - Java", candidates);

        assertThat(result.outcome()).isEqualTo(SpecializationNameMatcher.MatchOutcome.MATCHED);
        assertThat(result.value()).isEqualTo("Software Engineering - Java");
    }

    @Test
    void resolve_withNoCandidateMatching_returnsNoMatch() {
        Map<String, String> candidates = Map.of(
            SpecializationNameMatcher.normalize("Data Engineering"), "Data Engineering");

        var result = SpecializationNameMatcher.resolve("Quality Assurance", candidates);

        assertThat(result.outcome()).isEqualTo(SpecializationNameMatcher.MatchOutcome.NO_MATCH);
    }

    @Test
    void resolve_withBlankInput_returnsNoMatch() {
        Map<String, String> candidates = Map.of(
            SpecializationNameMatcher.normalize("Data Engineering"), "Data Engineering");

        var result = SpecializationNameMatcher.resolve("   ", candidates);

        assertThat(result.outcome()).isEqualTo(SpecializationNameMatcher.MatchOutcome.NO_MATCH);
    }
}
