package com.amalitech.labresultsvalidator.domain.notification.repository;

import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findBySyncJobId(UUID syncJobId);

    List<Notification> findBySyncJobIdAndStatusAndDispatchPolicy(
        UUID syncJobId, String status, String dispatchPolicy);

    long countBySyncJobIdAndStatusAndDispatchPolicy(
        UUID syncJobId, String status, String dispatchPolicy);

    /** Run-Review screen listing, filtered by any combination of cohort/sync job/status/type/recipient kind. */
    @Query("SELECT n FROM Notification n WHERE "
        + "(:cohortId IS NULL OR n.cohortId = :cohortId) AND "
        + "(:syncJobId IS NULL OR n.syncJobId = :syncJobId) AND "
        + "(:status IS NULL OR n.status = :status) AND "
        + "(:type IS NULL OR n.type = :type) AND "
        + "(:recipientKind IS NULL OR n.recipientKind = :recipientKind)")
    Page<Notification> search(
        @Param("cohortId") UUID cohortId,
        @Param("syncJobId") UUID syncJobId,
        @Param("status") String status,
        @Param("type") String type,
        @Param("recipientKind") String recipientKind,
        Pageable pageable);
}