package com.amalitech.labresultsvalidator.domain.cohort.dto;

import com.amalitech.labresultsvalidator.common.validation.DateRangeHolder;
import com.amalitech.labresultsvalidator.common.validation.EndDateAfterStartDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EndDateAfterStartDate
public class CreateCohortRequest implements DateRangeHolder {

    @NotBlank(message = "name is required")
    private String name;

    @NotNull(message = "startDate is required")
    private LocalDate startDate;

    @NotNull(message = "endDate is required")
    private LocalDate endDate;
}
