package com.amalitech.labresultsvalidator.domain.learner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Schema(description = "Request body for creating a single learner")
@Getter
@NoArgsConstructor
public class CreateLearnerRequest {

    @Schema(description = "Full name of the learner", example = "Ama Owusu")
    @NotBlank(message = "Full name is required")
    @Size(max = 200, message = "Full name must not exceed 200 characters")
    private String fullName;

    @Schema(description = "Unique email address of the learner",
        example = "ama.owusu@learner.labgate.com")
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    private String email;

    @Schema(description = "UUID of the cohort to enrol this learner in")
    @NotNull(message = "Cohort ID is required")
    private UUID cohortId;

    @Schema(description = "UUID of the specialization within the cohort")
    @NotNull(message = "Specialization ID is required")
    private UUID specializationId;
}
