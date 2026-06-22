package com.amalitech.labresultsvalidator.domain.csvUploads.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.csvUploads.dto.CsvUploadResponse;
import com.amalitech.labresultsvalidator.domain.csvUploads.entity.CsvUpload;
import com.amalitech.labresultsvalidator.domain.csvUploads.repository.CsvUploadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CsvUploadService {

    private final CsvUploadRepository csvUploadRepository;

    public PagedResponse<CsvUploadResponse> ListUploads(Pageable pageable) {
        return PagedResponse.of(
            csvUploadRepository.findAllByOrderByUploadedAtDesc(pageable)
                .map(this::mapToResponse)
        );
    }

    public CsvUploadResponse getUploadById(UUID id) {
        CsvUpload upload = csvUploadRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("CSV upload not found with id: " + id + " 'not found"));
        return mapToResponse(upload);
    }

    public Map<String, Object> getErrorReport(UUID id) {
        CsvUpload upload = csvUploadRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Upload with id '" + id + "' not found"));
        if (upload.getErrorReportJson() == null) {
            throw new ResourceNotFoundException(
                "No error report found for upload '" + id + "'");
        }
        return upload.getErrorReportJson();
    }

    private CsvUploadResponse mapToResponse(CsvUpload upload) {
        return CsvUploadResponse.builder()
            .id(upload.getId())
            .uploadedByEmail(upload.getUploadedByUser().getEmail())
            .filename(upload.getFilename())
            .fileSha256(upload.getFileSha256())
            .uploadedAt(upload.getUploadedAt())
            .totalRows(upload.getTotalRows())
            .acceptedRows(upload.getAcceptedRows())
            .rejectedRows(upload.getRejectedRows())
            .status(upload.getStatus().name())
            .createdAt(upload.getCreatedAt())
            .updatedAt(upload.getUpdatedAt())
            .build();
    }
}