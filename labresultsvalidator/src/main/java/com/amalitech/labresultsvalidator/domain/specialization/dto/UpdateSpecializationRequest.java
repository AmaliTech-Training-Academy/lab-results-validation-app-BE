package com.amalitech.labresultsvalidator.domain.specialization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateSpecializationRequest {

    @NotBlank(message = "Specialization name is required")
    @Size(max = 150, message = "Specialization name must not exceed 150 characters")
    private String name;

    @NotBlank(message = "Specialization code is required")
    @Size(max = 20, message = "Specialization code must not exceed 20 characters")
    private String code;
}
