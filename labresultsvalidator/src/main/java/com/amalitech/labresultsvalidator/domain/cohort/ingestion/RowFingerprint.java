package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import com.amalitech.labresultsvalidator.common.utils.Sha256Util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * The B8 change-detection fingerprint: {@code hash(submitted_on, nsp_name, score)}. Computed
 * identically at classification time (to compare against a committed row's stored hash) and at
 * commit time (to store on a new/updated row) — kept in one place so the two never drift.
 */
public final class RowFingerprint {

    private RowFingerprint() {
    }

    public static String compute(LocalDate submittedOn, String nspName, BigDecimal score) {
        String canonical = submittedOn + "|" + nspName + "|"
            + score.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return Sha256Util.sha256Hex(canonical);
    }
}
