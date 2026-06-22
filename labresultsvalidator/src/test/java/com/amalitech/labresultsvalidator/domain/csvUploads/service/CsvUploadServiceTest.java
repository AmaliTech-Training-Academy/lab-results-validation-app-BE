package com.amalitech.labresultsvalidator.domain.csvUploads.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.csvUploads.dto.CsvUploadResponse;
import com.amalitech.labresultsvalidator.domain.csvUploads.entity.CsvUpload;
import com.amalitech.labresultsvalidator.domain.csvUploads.repository.CsvUploadRepository;
import com.amalitech.labresultsvalidator.domain.enums.UploadStatus;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CsvUploadServiceTest {

    @Mock private CsvUploadRepository csvUploadRepository;

    @InjectMocks
    private CsvUploadService csvUploadService;

    private User buildUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("instructor@test.com")
                .passwordHash("hashed")
                .role(UserRole.INSTRUCTOR)
                .isActive(true)
                .mustChangePassword(false)
                .build();
    }

    private CsvUpload buildUpload(UUID id, Map<String, Object> errorReport) {
        OffsetDateTime now = OffsetDateTime.now();
        return CsvUpload.builder()
                .id(id)
                .uploadedByUser(buildUser())
                .filename("results.csv")
                .fileSha256("abc123def456abc123def456abc123def456abc123def456abc123def456abcd")
                .uploadedAt(now)
                .totalRows(100)
                .acceptedRows(90)
                .rejectedRows(10)
                .status(UploadStatus.COMPLETED)
                .errorReportJson(errorReport)
                .build();
    }

    // --- listUploads ---

    @Test
    void listUploads_returnsPagedResponseMappedFromRepository() {
        UUID id = UUID.randomUUID();
        CsvUpload upload = buildUpload(id, null);
        Page<CsvUpload> page = new PageImpl<>(List.of(upload), PageRequest.of(0, 10), 1);
        when(csvUploadRepository.findAllByOrderByUploadedAtDesc(any(Pageable.class))).thenReturn(page);

        PagedResponse<CsvUploadResponse> result = csvUploadService.ListUploads(PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(id);
        assertThat(result.getContent().get(0).getUploadedByEmail()).isEqualTo("instructor@test.com");
        assertThat(result.getContent().get(0).getFilename()).isEqualTo("results.csv");
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void listUploads_whenNoUploads_returnsEmptyPage() {
        Page<CsvUpload> empty = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(csvUploadRepository.findAllByOrderByUploadedAtDesc(any(Pageable.class))).thenReturn(empty);

        PagedResponse<CsvUploadResponse> result = csvUploadService.ListUploads(PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    // --- getUploadById ---

    @Test
    void getUploadById_whenFound_returnsMappedResponse() {
        UUID id = UUID.randomUUID();
        CsvUpload upload = buildUpload(id, null);
        when(csvUploadRepository.findById(id)).thenReturn(Optional.of(upload));

        CsvUploadResponse response = csvUploadService.getUploadById(id);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getFilename()).isEqualTo("results.csv");
        assertThat(response.getUploadedByEmail()).isEqualTo("instructor@test.com");
        assertThat(response.getTotalRows()).isEqualTo(100);
        assertThat(response.getAcceptedRows()).isEqualTo(90);
        assertThat(response.getRejectedRows()).isEqualTo(10);
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void getUploadById_whenNotFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(csvUploadRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> csvUploadService.getUploadById(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- getErrorReport ---

    @Test
    void getErrorReport_whenUploadHasReport_returnsReport() {
        UUID id = UUID.randomUUID();
        Map<String, Object> report = Map.of("row", 1, "field", "SCORE", "message", "Invalid score");
        CsvUpload upload = buildUpload(id, report);
        when(csvUploadRepository.findById(id)).thenReturn(Optional.of(upload));

        Map<String, Object> result = csvUploadService.getErrorReport(id);

        assertThat(result).containsKey("row");
        assertThat(result).containsKey("field");
        assertThat(result).containsKey("message");
    }

    @Test
    void getErrorReport_whenUploadNotFound_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(csvUploadRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> csvUploadService.getErrorReport(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void getErrorReport_whenErrorReportIsNull_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        CsvUpload upload = buildUpload(id, null);
        when(csvUploadRepository.findById(id)).thenReturn(Optional.of(upload));

        assertThatThrownBy(() -> csvUploadService.getErrorReport(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No error report");
    }
}