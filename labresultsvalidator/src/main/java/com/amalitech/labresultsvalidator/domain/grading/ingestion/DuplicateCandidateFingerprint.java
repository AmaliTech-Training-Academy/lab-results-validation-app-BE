package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.common.utils.Sha256Util;
import com.amalitech.labresultsvalidator.domain.grading.dto.ConflictCandidate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Identifies a held duplicate by its <em>candidate set</em> — which sheet rows are duplicated and
 * what marks they carry (B10 AC3).
 *
 * <p>Resolving a duplicate doesn't remove it from the workbook (there is no write-back to
 * SharePoint), so the next run sees it again. This digest is how a run tells "the same duplicate,
 * untouched in the sheet" — don't raise it again, don't re-alert — from "the marks or rows changed",
 * which is a genuinely new decision for the admin to take. Without it, one duplicate pair produced
 * fresh conflicts and a fresh alert email on every run.
 *
 * <p>Deliberately <strong>not</strong> stored on the conflict: everything it hashes already lives in
 * {@code incoming_payload_json}, so it is derived on demand from either side — the incoming rows of
 * the run in progress, or the candidates parsed back off a stored conflict. Both go through
 * {@link #canonical} so the two can never disagree, the same reason {@link RowFingerprint} keeps its
 * classification-time and commit-time forms in one place.
 *
 * <p>Order-independent: candidates are sorted by sheet then row, so re-ordering the copies within a
 * sheet does not read as a change. {@code fileName} is excluded — a renamed workbook holding the
 * identical duplicate is the same unresolved problem.
 */
public final class DuplicateCandidateFingerprint {

    private DuplicateCandidateFingerprint() {
    }

    /** Digest for the incoming copies of one duplicated row, at classification/commit time. */
    public static String ofRows(List<ValidatedScoreRow> rows) {
        return Sha256Util.sha256Hex(rows.stream()
            .map(row -> canonical(row.sheetName(), row.rowNum(), row.score(), row.submittedOn()))
            .sorted()
            .collect(Collectors.joining("\n")));
    }

    /** Digest for the candidates already held on a stored conflict. */
    public static String ofCandidates(List<ConflictCandidate> candidates) {
        return Sha256Util.sha256Hex(candidates.stream()
            .map(c -> canonical(c.sheetName(), c.rowNum(), c.score(), c.submittedOn()))
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.joining("\n")));
    }

    /**
     * One candidate's canonical line. Score is scaled to 2dp exactly as {@link RowFingerprint} does,
     * so "88" and "88.00" — both shapes appear in stored payloads — hash the same.
     */
    private static String canonical(String sheetName, Integer rowNum, BigDecimal score, LocalDate submittedOn) {
        return String.valueOf(sheetName)
            + "|" + rowNum
            + "|" + (score == null ? "null" : score.setScale(2, RoundingMode.HALF_UP).toPlainString())
            + "|" + submittedOn;
    }
}
