package com.amalitech.labresultsvalidator.domain.learner.dto;

import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Schema(description = "Result of a bulk learner CSV upload")
@Getter
@Builder
public class BulkUploadResponse {

    @Schema(description = "Number of rows successfully committed", example = "45")
    private int acceptedCount;

    @Schema(description = "Number of rows rejected", example = "3")
    private int rejectedCount;

    @Schema(description = "Per-row errors for rejected rows")
    private List<CsvRowError> errors;
}
