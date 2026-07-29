package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CohortSyncFileRepository extends JpaRepository<CohortSyncFile, UUID> {

    List<CohortSyncFile> findByCohortIdAndFileNameOrderByCreatedAtDesc(UUID cohortId, String fileName);
}