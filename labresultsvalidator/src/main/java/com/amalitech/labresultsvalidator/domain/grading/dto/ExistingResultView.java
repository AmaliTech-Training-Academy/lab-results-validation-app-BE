package com.amalitech.labresultsvalidator.domain.grading.dto;

import com.amalitech.labresultsvalidator.domain.grading.entity.LabResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The currently stored grade a duplicate is being decided against — the "existing" side of the
 * merge-style comparison in B10 AC1.
 *
 * <p>The conflict itself only stores {@code existing_result_id}, so the queue could show the admin an
 * opaque UUID and no mark. Choosing between two incoming marks without seeing the one already stored
 * is what made the decision unsafe.
 */
public record ExistingResultView(
    UUID id,
    BigDecimal score,
    LocalDate submittedOn,
    UUID instructorContactId,
    String reviewerName
) {

    public static ExistingResultView from(LabResult result) {
        return new ExistingResultView(result.getId(), result.getScore(), result.getSubmittedOn(),
            result.getInstructorContactId(), null);
    }

    public ExistingResultView withReviewerName(String resolvedReviewerName) {
        return new ExistingResultView(id, score, submittedOn, instructorContactId, resolvedReviewerName);
    }
}
