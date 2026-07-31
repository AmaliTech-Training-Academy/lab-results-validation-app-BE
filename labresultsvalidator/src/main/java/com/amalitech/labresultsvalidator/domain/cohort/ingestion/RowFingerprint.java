package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import com.amalitech.labresultsvalidator.common.utils.Sha256Util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * The B8 change-detection fingerprint: {@code hash(submitted_on, score)}. Row identity is
 * {@code (learnerId, labId)} — see {@code ScoreRowClassifier} — those don't change between
 * ingestion runs, so they're not part of the fingerprint; submitted_on and score are the fields a
 * re-grade can actually change. Computed identically at classification time (to compare against a
 * committed row's stored hash) and at commit time (to store on a new/updated row) — kept in one
 * place so the two never drift.
 */
public final class RowFingerprint {

    private RowFingerprint() {
    }

    public static String compute(LocalDate submittedOn, BigDecimal score) {
        String canonical = submittedOn + "|" + score.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return Sha256Util.sha256Hex(canonical);
    }
}
