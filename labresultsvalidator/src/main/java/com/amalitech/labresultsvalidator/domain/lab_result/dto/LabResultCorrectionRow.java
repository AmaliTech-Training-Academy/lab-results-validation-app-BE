package com.amalitech.labresultsvalidator.domain.lab_result.dto;

import com.opencsv.bean.CsvBindByName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * OpenCSV-annotated bean for the downloadable corrections file: the original columns of every
 * rejected row from a bulk upload, plus a trailing {@code ERROR_MESSAGE} column describing why the
 * row failed. The instructor fixes the listed rows and re-uploads.
 *
 * <p>The first ten columns mirror {@link LabResultCsvRow} in the same declared order so the file can
 * be re-uploaded directly; the upload parser ignores the extra {@code ERROR_MESSAGE} column, so it
 * does not even need to be removed first. Column order on write is driven by field-declaration order
 * (see {@code CsvWriterService}).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResultCorrectionRow {

    @CsvBindByName(column = "LEARNER_EMAIL")
    private String learnerEmail;

    @CsvBindByName(column = "COHORT_NAME")
    private String cohortName;

    @CsvBindByName(column = "SPECIALIZATION_NAME")
    private String specializationName;

    @CsvBindByName(column = "MODULE_NAME")
    private String moduleName;

    @CsvBindByName(column = "LAB_TITLE")
    private String labTitle;

    @CsvBindByName(column = "SCORE")
    private String score;

    @CsvBindByName(column = "MAX_SCORE")
    private String maxScore;

    @CsvBindByName(column = "ATTEMPT_NUMBER")
    private String attemptNumber;

    @CsvBindByName(column = "SUBMITTED_ON")
    private String submittedOn;

    @CsvBindByName(column = "GRADED_BY")
    private String gradedBy;

    @CsvBindByName(column = "ERROR_MESSAGE")
    private String errorMessage;
}
