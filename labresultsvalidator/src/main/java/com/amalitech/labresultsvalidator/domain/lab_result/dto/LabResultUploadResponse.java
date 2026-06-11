package com.amalitech.labresultsvalidator.domain.lab_result.dto;

import com.amalitech.labresultsvalidator.common.csv.CsvRowError;
import com.amalitech.labresultsvalidator.domain.enums.UploadStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * Result of a bulk lab-result CSV upload. Rejected rows are described per-row in {@link #errors},
 * each carrying the offending field and the validation rule that was broken.
 */
@Schema(description = "Result of a bulk lab-result CSV upload")
@Getter
@Builder
public class LabResultUploadResponse {

    @Schema(description = "Id of the csv_uploads audit record created for this upload")
    private UUID uploadId;

    @Schema(description = "Total data rows found in the file", example = "50")
    private int totalRows;

    @Schema(description = "New results inserted", example = "40")
    private int insertedCount;

    @Schema(description = "Existing results updated with corrected values", example = "5")
    private int updatedCount;

    @Schema(description = "Rows skipped because an identical result already exists", example = "2")
    private int skippedCount;

    @Schema(description = "Rows rejected by validation", example = "3")
    private int rejectedCount;

    @Schema(description = "Processing status of the upload")
    private UploadStatus status;

    @Schema(description = "Per-row errors for rejected rows (field + rule + message)")
    private List<CsvRowError> errors;
}
