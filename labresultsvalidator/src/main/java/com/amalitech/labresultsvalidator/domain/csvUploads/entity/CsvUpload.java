package com.amalitech.labresultsvalidator.domain.csvUploads.entity;

import com.amalitech.labresultsvalidator.domain.lab_result.entity.LabResult;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.enums.UploadStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "csv_uploads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CsvUpload {

    /** Maximum length of the filename column. */
    private static final int FILENAME_MAX_LENGTH = 255;

    /** Fixed length of a SHA-256 hex digest. */
    private static final int SHA256_LENGTH = 64;

    /** Unique identifier for this upload record. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The user who uploaded this CSV file. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user_id", nullable = false)
    private User uploadedByUser;

    /** Original filename of the uploaded CSV. */
    @Column(name = "filename", nullable = false,
        length = FILENAME_MAX_LENGTH)
    private String filename;

    /** SHA-256 hex digest of the file used for deduplication. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "file_sha256", nullable = false,
        unique = true, length = SHA256_LENGTH)
    private String fileSha256;

    /** Timestamp when the file was received from the client. */
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;

    /** Total number of data rows found in the CSV. */
    @Builder.Default
    @Column(name = "total_rows", nullable = false)
    private int totalRows = 0;

    /** Number of rows that passed validation and were accepted. */
    @Builder.Default
    @Column(name = "accepted_rows", nullable = false)
    private int acceptedRows = 0;

    /** Number of rows that failed validation and were rejected. */
    @Builder.Default
    @Column(name = "rejected_rows", nullable = false)
    private int rejectedRows = 0;

    /** Current processing status of this upload. */
    @Builder.Default
    @Column(name = "status", nullable = false)
    private UploadStatus status = UploadStatus.PROCESSING;

    /** Structured error details for rejected rows stored as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_report_json", columnDefinition = "jsonb")
    private Map<String, Object> errorReportJson;

    /** Timestamp when this record was created. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when this record was last updated. */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Lab results parsed and imported from this CSV upload. */
    @OneToMany(mappedBy = "csvUpload",
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    private List<LabResult> labResults;
}
