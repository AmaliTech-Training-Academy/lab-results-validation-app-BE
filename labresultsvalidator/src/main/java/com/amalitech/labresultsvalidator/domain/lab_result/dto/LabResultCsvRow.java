package com.amalitech.labresultsvalidator.domain.lab_result.dto;

import com.opencsv.bean.CsvBindByName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * OpenCSV-annotated bean used both for parsing lab-result bulk upload files and for generating the
 * downloadable CSV template. Column names are upper-cased to match the template headers.
 *
 * <p>Every field is bound as a raw {@code String} on purpose: numeric, enum, and date conversion is
 * performed by the upload service so that each malformed value produces a clean field- and
 * rule-specific error (V4–V7) rather than an opaque OpenCSV binding exception. The {@code required}
 * flag drives the header-presence check (V1) in {@code CsvParserService}.
 */
@Getter
@Setter
@NoArgsConstructor
public class LabResultCsvRow {

    @CsvBindByName(column = "LEARNER_EMAIL", required = true)
    private String learnerEmail;

    @CsvBindByName(column = "COHORT_NAME", required = true)
    private String cohortName;

    @CsvBindByName(column = "SPECIALIZATION_NAME", required = true)
    private String specializationName;

    @CsvBindByName(column = "MODULE_NAME", required = true)
    private String moduleName;

    @CsvBindByName(column = "LAB_TITLE", required = true)
    private String labTitle;

    @CsvBindByName(column = "SCORE", required = true)
    private String score;

    @CsvBindByName(column = "MAX_SCORE", required = true)
    private String maxScore;

    @CsvBindByName(column = "ATTEMPT_NUMBER", required = true)
    private String attemptNumber;

    @CsvBindByName(column = "SUBMITTED_ON", required = true)
    private String submittedOn;

    @CsvBindByName(column = "GRADED_BY")
    private String gradedBy;
}
