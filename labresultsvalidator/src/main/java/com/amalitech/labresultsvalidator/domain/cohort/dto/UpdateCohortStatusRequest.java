package com.amalitech.labresultsvalidator.domain.cohort.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Request body for activating or deactivating a cohort")
@Getter
@NoArgsConstructor
public class UpdateCohortStatusRequest {

    @Schema(description = "true to activate the cohort, false to deactivate it", example = "false")
    @NotNull(message = "active is required")
    private Boolean active;
}
