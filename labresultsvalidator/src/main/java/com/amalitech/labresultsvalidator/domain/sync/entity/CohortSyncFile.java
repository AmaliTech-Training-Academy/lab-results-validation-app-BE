package com.amalitech.labresultsvalidator.domain.sync.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "cohort_sync_files")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CohortSyncFile extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_job_id", nullable = false)
    private CohortSyncJob syncJob;

    @Column(name = "s3_key", nullable = false, length = 500)
    private String s3Key;

    @Column(name = "s3_version_id", length = 200)
    private String s3VersionId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "scenario_folder", length = 255)
    private String scenarioFolder;

    /**
     * Stable drive item id of the source file. Recorded for provenance: unlike the filename
     * it survives renames, so it identifies which SharePoint item a row describes.
     */
    @Column(name = "sharepoint_item_id", length = 200)
    private String sharepointItemId;

    /** SharePoint's server-computed content hash, from the single-item GET (B3 AC1). */
    @Column(name = "quick_xor_hash", length = 128)
    private String quickXorHash;

    /** Content tag (cTag) from the single-item GET (B3 AC1). */
    @Column(name = "sharepoint_version_id", length = 200)
    private String sharepointVersionId;

    /** SHA-256 (hex) over the exact bytes downloaded and handed to POI (B4 AC3). */
    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    /**
     * What change detection concluded for this file in this run. A row exists for every file
     * the run reached, so a missing row means "not reached", never "unchanged" (B3 AC2).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "change_state", length = 20)
    private SyncFileChangeState changeState;
}