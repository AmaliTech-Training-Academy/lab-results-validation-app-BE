package com.amalitech.labresultsvalidator.domain.learner.dto;

import com.opencsv.bean.CsvBindByName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * OpenCSV-annotated bean used both for parsing bulk upload files and for generating
 * the downloadable CSV template. Column names are upper-cased to match the template headers.
 */
@Getter
@Setter
@NoArgsConstructor
public class LearnerCsvRow {

    @CsvBindByName(column = "FULL_NAME", required = true)
    private String fullName;

    @CsvBindByName(column = "EMAIL", required = true)
    private String email;

    @CsvBindByName(column = "COHORT_NAME", required = true)
    private String cohortName;

    @CsvBindByName(column = "SPECIALIZATION_NAME", required = true)
    private String specializationName;
}
