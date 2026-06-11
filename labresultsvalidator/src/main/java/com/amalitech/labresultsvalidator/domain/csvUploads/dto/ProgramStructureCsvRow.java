package com.amalitech.labresultsvalidator.domain.csvUploads.dto;

import com.opencsv.bean.CsvBindByName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ProgramStructureCsvRow {

    @CsvBindByName(column = "COHORT_NAME", required = true)
    private String cohortName;

    @CsvBindByName(column = "SPECIALIZATION_NAME", required = true)
    private String specializationName;

    @CsvBindByName(column = "SPECIALIZATION_CODE", required = true)
    private String specializationCode;

    @CsvBindByName(column = "MODULE_NAME", required = true)
    private String moduleName;

    @CsvBindByName(column = "LAB_TITLE", required = true)
    private String labTitle;

    @CsvBindByName(column = "MAX_SCORE", required = true)
    private String maxScore;
}