package com.amalitech.labresultsvalidator.domain.cohort.repository;

import com.amalitech.labresultsvalidator.domain.cohort.entity.LabReferenceAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LabReferenceAuditLogRepository extends JpaRepository<LabReferenceAuditLog, UUID> {
}
