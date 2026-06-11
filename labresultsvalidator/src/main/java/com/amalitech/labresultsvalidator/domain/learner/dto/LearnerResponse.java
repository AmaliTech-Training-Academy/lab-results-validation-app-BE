package com.amalitech.labresultsvalidator.domain.learner.dto;

import com.amalitech.labresultsvalidator.domain.enums.LearnerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Learner read model")
@Getter
@Builder
public class LearnerResponse {

    @Schema(description = "Unique identifier of the learner")
    private UUID id;

    @Schema(description = "Full name", example = "Ama Owusu")
    private String fullName;

    @Schema(description = "Email address", example = "ama.owusu@learner.labgate.com")
    private String email;

    @Schema(description = "ID of the enrolled cohort")
    private UUID cohortId;

    @Schema(description = "Name of the enrolled cohort", example = "Cohort 1 — Spring 2026")
    private String cohortName;

    @Schema(description = "ID of the enrolled specialization")
    private UUID specializationId;

    @Schema(description = "Name of the enrolled specialization", example = "Data Analytics")
    private String specializationName;

    @Schema(description = "Enrolment status", example = "ACTIVE")
    private LearnerStatus status;

    @Schema(description = "Record creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Record last-updated timestamp")
    private OffsetDateTime updatedAt;
}
