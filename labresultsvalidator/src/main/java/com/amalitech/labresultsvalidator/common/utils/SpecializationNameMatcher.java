package com.amalitech.labresultsvalidator.common.utils;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves a free-text specialization value (from the Learners reference sheet) against a set of
 * known specialization names. An exact normalized match always wins; substring containment is only
 * used as a fallback, and only when it identifies exactly one candidate — an ambiguous substring
 * match (e.g. two specialization names sharing a common word) must not silently resolve to whichever
 * candidate happens to be encountered first.
 */
public final class SpecializationNameMatcher {

    private SpecializationNameMatcher() {
    }

    public enum MatchOutcome {
        MATCHED,
        NO_MATCH,
        AMBIGUOUS
    }

    public record MatchResult<T>(MatchOutcome outcome, T value) {
        public static <T> MatchResult<T> matched(T value) {
            return new MatchResult<>(MatchOutcome.MATCHED, value);
        }

        public static <T> MatchResult<T> noMatch() {
            return new MatchResult<>(MatchOutcome.NO_MATCH, null);
        }

        public static <T> MatchResult<T> ambiguous() {
            return new MatchResult<>(MatchOutcome.AMBIGUOUS, null);
        }
    }

    public static <T> MatchResult<T> resolve(
            String traineeSpec, Map<String, T> candidatesByNormalizedName) {
        if (traineeSpec == null || traineeSpec.isBlank()) {
            return MatchResult.noMatch();
        }
        String key = normalize(traineeSpec);

        T exact = candidatesByNormalizedName.get(key);
        if (exact != null) {
            return MatchResult.matched(exact);
        }

        T onlyMatch = null;
        int matchCount = 0;
        for (Map.Entry<String, T> entry : candidatesByNormalizedName.entrySet()) {
            if (key.contains(entry.getKey()) || entry.getKey().contains(key)) {
                matchCount++;
                if (matchCount > 1) {
                    return MatchResult.ambiguous();
                }
                onlyMatch = entry.getValue();
            }
        }
        return matchCount == 1 ? MatchResult.matched(onlyMatch) : MatchResult.noMatch();
    }

    public static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
