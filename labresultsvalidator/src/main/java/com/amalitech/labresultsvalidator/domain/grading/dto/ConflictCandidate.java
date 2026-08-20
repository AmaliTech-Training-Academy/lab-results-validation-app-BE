package com.amalitech.labresultsvalidator.domain.grading.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One conflicting incoming row held under an in-file duplicate (B10 AC1 — "the existing committed
 * record (if any) alongside <em>each</em> conflicting incoming row"). A duplicate holds two or more
 * of these; the admin picks one by {@link #index}.
 *
 * <p>{@code index} is the 0-based position in the conflict's candidate list and is what
 * {@code ResolveConflictRequest.chosenRowIndex} refers to. It is assigned on read from the stored
 * order, so it is stable for a given conflict.
 *
 * <p>{@code reviewerName} is not stored on the conflict (the payload keeps the resolved
 * {@code instructorContactId}); it is filled in by {@code IngestionConflictViewAssembler} and is
 * null everywhere else.
 *
 * <p>Fields are null when the stored payload for that candidate was incomplete or unparseable — see
 * {@code ConflictPayloadCodec}, which reads leniently so a corrupt row still lists rather than failing
 * the whole queue. {@code payloadIntact} is false when a value was present but could not be read,
 * which is the difference between "this row has no reviewer" and "this row's stored reviewer is
 * garbage": the first is normal (an unresolved reviewer is non-blocking, B6 AC4), the second must not
 * be committed as if the field had simply been empty.
 */
public record ConflictCandidate(
    int index,
    String fileName,
    String sheetName,
    Integer rowNum,
    String nspName,
    BigDecimal score,
    LocalDate submittedOn,
    UUID instructorContactId,
    String reviewerName,
    boolean payloadIntact
) {

    /** "sheet Module-1 row 5" — the same phrasing {@code ValidatedScoreRow.location()} uses. */
    public String location() {
        return "sheet " + sheetName + " row " + rowNum;
    }

    /** True when this candidate carries everything needed to commit it as authoritative. */
    public boolean isCommittable() {
        return payloadIntact && score != null && submittedOn != null;
    }

    public ConflictCandidate withReviewerName(String resolvedReviewerName) {
        return new ConflictCandidate(index, fileName, sheetName, rowNum, nspName, score, submittedOn,
            instructorContactId, resolvedReviewerName, payloadIntact);
    }
}
