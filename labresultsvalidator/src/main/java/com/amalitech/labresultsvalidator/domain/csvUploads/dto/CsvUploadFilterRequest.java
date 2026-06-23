package com.amalitech.labresultsvalidator.domain.csvUploads.dto;

import com.amalitech.labresultsvalidator.domain.enums.UploadStatus;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;

@Getter
@Setter
public class CsvUploadFilterRequest {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime startDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime endDate;

    private String uploadedByEmail;

    private UploadStatus status;

    /** Matches against filename (contains) or upload ID (exact). */
    private String search;
}
