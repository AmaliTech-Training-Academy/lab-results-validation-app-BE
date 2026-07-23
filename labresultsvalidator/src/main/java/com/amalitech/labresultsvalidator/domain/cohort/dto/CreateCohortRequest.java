package com.amalitech.labresultsvalidator.domain.cohort.dto;

import com.amalitech.labresultsvalidator.common.validation.DateRangeHolder;
import com.amalitech.labresultsvalidator.common.validation.EndDateAfterStartDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

@EndDateAfterStartDate
public record CreateCohortRequest(
    @NotBlank(message = "Cohort name is required") String name,
    @NotNull(message = "Start date is required") LocalDate startDate,
    @NotNull(message = "End date is required") LocalDate endDate
) implements DateRangeHolder {

    @Override
    public LocalDate getStartDate() {
        return startDate;
    }

    @Override
    public LocalDate getEndDate() {
        return endDate;
    }
}
