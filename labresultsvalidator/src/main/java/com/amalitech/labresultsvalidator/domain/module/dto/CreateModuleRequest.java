package com.amalitech.labresultsvalidator.domain.module.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
public class CreateModuleRequest {

    @NotBlank(message = "Module name is required")
    private String name;

    @NotNull(message = "Cohort ID is required")
    private UUID cohortId;

    @NotNull(message = "Specialization ID is required")
    private UUID specializationId;
}
