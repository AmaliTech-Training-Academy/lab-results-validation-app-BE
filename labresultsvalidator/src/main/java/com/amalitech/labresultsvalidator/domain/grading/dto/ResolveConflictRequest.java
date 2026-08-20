package com.amalitech.labresultsvalidator.domain.grading.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The admin's single decision on a held in-file duplicate (B10 AC2).
 *
 * <p>{@code chosenRowIndex} says <em>which</em> of the conflict's candidate rows is authoritative:
 * the 0-based {@link ConflictCandidate#index()} from the conflict's {@code candidates} list. It is
 * required with {@code KEEP_INCOMING} whenever a duplicate holds more than one candidate — the point
 * of the fix is that "keep the incoming row" is meaningless when there are two of them with different
 * marks, which is how a resolution could previously commit whichever copy happened to be clicked
 * first. Ignored for {@code KEEP_EXISTING}/{@code REJECT}, which discard every candidate.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolveConflictRequest {

    @NotNull(message = "action is required")
    private ConflictResolutionAction action;

    @PositiveOrZero(message = "chosenRowIndex cannot be negative")
    private Integer chosenRowIndex;

    private String note;
}
