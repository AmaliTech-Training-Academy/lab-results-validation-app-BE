package com.amalitech.labresultsvalidator.domain.cohort.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class UpdateCohortRequest {

    @Size(max = 150, message = "Cohort name must not exceed 150 characters")
    private String name;

    private LocalDate startDate;

    private LocalDate endDate;

    private Boolean active;
}
