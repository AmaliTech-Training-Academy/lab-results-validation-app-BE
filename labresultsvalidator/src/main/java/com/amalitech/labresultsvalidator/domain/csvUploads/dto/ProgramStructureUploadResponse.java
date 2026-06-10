package com.amalitech.labresultsvalidator.domain.csvUploads.dto;

import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Schema(description = "Result of a bulk program structure CSV upload")
@Getter
@Builder
public class ProgramStructureUploadResponse {

    @Schema(description = "Number of specializations created", example = "2")
    private int specializationsCreated;

    @Schema(description = "Number of modules created", example = "4")
    private int modulesCreated;

    @Schema(description = "Number of labs created", example = "10")
    private int labsCreated;

    @Schema(description = "Row-level errors — populated only when the upload is rejected entirely")
    private List<CsvRowError> errors;
}