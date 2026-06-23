package com.amalitech.labresultsvalidator.domain.csvUploads.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
public class CsvUploadResponse {
    private UUID id;
    private String uploadedByEmail;
    private String filename;
    private String fileSha256;
    private OffsetDateTime uploadedAt;
    private int totalRows;
    private int acceptedRows;
    private int rejectedRows;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}