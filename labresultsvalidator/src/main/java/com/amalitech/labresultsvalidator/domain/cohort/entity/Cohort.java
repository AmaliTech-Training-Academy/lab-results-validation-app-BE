package com.amalitech.labresultsvalidator.domain.cohort.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "cohorts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cohort extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, unique = true, length = 150)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Builder.Default
    @Column(name = "lifecycle_state", nullable = false, length = 30)
    private String lifecycleState = "DRAFT";

    @Builder.Default
    @Column(name = "is_locked", nullable = false)
    private boolean isLocked = false;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "sharepoint_folder_url")
    private String sharepointFolderUrl;

    @Column(name = "sharepoint_drive_id", length = 200)
    private String sharepointDriveId;

    @Column(name = "sharepoint_item_id", length = 200)
    private String sharepointItemId;

    @Column(name = "reference_accepted_at")
    private OffsetDateTime referenceAcceptedAt;

    @Column(name = "reference_accepted_by")
    private UUID referenceAcceptedBy;
}
