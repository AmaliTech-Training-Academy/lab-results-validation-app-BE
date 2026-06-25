package com.amalitech.labresultsvalidator.domain.cohort.dto;

import com.amalitech.labresultsvalidator.common.validation.DateRangeHolder;
import com.amalitech.labresultsvalidator.common.validation.EndDateAfterStartDate;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
@EndDateAfterStartDate
public class UpdateCohortRequest implements DateRangeHolder {

    @Size(max = 150, message = "Cohort name must not exceed 150 characters")
    private String name;

    @FutureOrPresent(message = "Start date must be today or in the future")
    private LocalDate startDate;

    @Future(message = "End date must be in the future")
    private LocalDate endDate;

    private Boolean active;
}
