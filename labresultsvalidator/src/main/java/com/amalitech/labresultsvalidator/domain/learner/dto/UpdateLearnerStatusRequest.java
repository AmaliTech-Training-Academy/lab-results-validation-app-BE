package com.amalitech.labresultsvalidator.domain.learner.dto;

import com.amalitech.labresultsvalidator.domain.enums.LearnerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Request body for archiving or reactivating a learner")
@Getter
@NoArgsConstructor
public class UpdateLearnerStatusRequest {

    @Schema(description = "New status — ACTIVE or ARCHIVED", example = "ARCHIVED")
    @NotNull(message = "Status is required")
    private LearnerStatus status;
}
