package com.amalitech.labresultsvalidator.domain.auditlog.repository;

import com.amalitech.labresultsvalidator.domain.auditlog.entity.LabReferenceAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LabReferenceAuditLogRepository extends JpaRepository<LabReferenceAuditLog, UUID> {
}
