package com.amalitech.labresultsvalidator.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.LocalDate;

public class EndDateAfterStartDateValidator
        implements ConstraintValidator<EndDateAfterStartDate, Object> {

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (!(value instanceof DateRangeHolder holder)) {
            return true;
        }
        LocalDate start = holder.getStartDate();
        LocalDate end = holder.getEndDate();
        if (start == null || end == null) {
            return true;
        }
        return end.isAfter(start);
    }
}
