package com.amalitech.labresultsvalidator.domain.learner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Schema(description = "Request body for updating a learner. Email is not updatable.")
@Getter
@NoArgsConstructor
public class UpdateLearnerRequest {

    @Schema(description = "Updated full name", example = "Ama Owusu-Mensah")
    @NotBlank(message = "Full name is required")
    @Size(max = 200, message = "Full name must not exceed 200 characters")
    private String fullName;

    @Schema(description = "UUID of the new cohort")
    @NotNull(message = "Cohort ID is required")
    private UUID cohortId;

    @Schema(description = "UUID of the new specialization — must belong to the given cohort")
    @NotNull(message = "Specialization ID is required")
    private UUID specializationId;
}
