package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.domain.cohort.dto.RowIssueSummary;
import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionRun;
import com.amalitech.labresultsvalidator.domain.cohort.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.cohort.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.IngestionRunRepository;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.amalitech.labresultsvalidator.domain.notification.event.SyncJobNotificationsStagedEvent;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stages (persists as {@code PENDING}) one {@code instructor_digest} {@link Notification} per
 * instructor and, if any row's reviewer couldn't be resolved, exactly one {@code admin_run_digest}
 * — bundled across every {@link IngestionRun}/file in the whole sync job, not per file. Staging
 * never sends email; dispatch is {@code NotificationDispatchService}'s concern, triggered off
 * {@link SyncJobNotificationsStagedEvent} after this method's transaction commits.
 */
@Service
@RequiredArgsConstructor
public class NotificationStagingService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationStagingService.class);

    private final IngestionRunRepository ingestionRunRepository;
    private final InstructorContactRepository instructorContactRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationSettingsService notificationSettingsService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public void stageForSyncJob(UUID cohortId, UUID syncJobId, UUID actorId) {
        List<IngestionRun> runs = ingestionRunRepository.findBySyncJobId(syncJobId);
        if (runs.isEmpty()) {
            return;
        }

        List<RowIssueSummary> allIssues = new ArrayList<>();
        for (IngestionRun run : runs) {
            allIssues.addAll(parseIssues(run.getErrorReportJson()));
        }
        if (allIssues.isEmpty()) {
            // Zero notifiable outcomes for this job — stage nothing, publish nothing.
            return;
        }

        Map<UUID, List<RowIssueSummary>> byInstructor = allIssues.stream()
            .filter(i -> i.instructorContactId() != null)
            .collect(Collectors.groupingBy(RowIssueSummary::instructorContactId));
        List<RowIssueSummary> unattributed = allIssues.stream()
            .filter(i -> i.instructorContactId() == null)
            .toList();

        boolean autoSendInstructorEmails = notificationSettingsService.getSettings().isAutoSendInstructorEmails();
        List<Notification> toStage = new ArrayList<>();

        if (!byInstructor.isEmpty()) {
            Map<UUID, InstructorContact> instructorsById = instructorContactRepository
                .findAllById(byInstructor.keySet()).stream()
                .collect(Collectors.toMap(InstructorContact::getId, ic -> ic));

            for (Map.Entry<UUID, List<RowIssueSummary>> entry : byInstructor.entrySet()) {
                InstructorContact instructor = instructorsById.get(entry.getKey());
                if (instructor == null) {
                    // Resolved at ingestion time, but the contact has since been removed — no one
                    // sane to send to; log and skip rather than fail the whole staging pass.
                    LOG.warn("[notification] syncJob={} instructor {} no longer exists, skipping digest",
                        syncJobId, entry.getKey());
                    continue;
                }
                toStage.add(buildInstructorDigest(cohortId, syncJobId, instructor, entry.getValue(),
                    autoSendInstructorEmails));
            }
        }

        if (!unattributed.isEmpty()) {
            userRepository.findFirstByRoleAndIsActiveTrueOrderByCreatedAtAsc(UserRole.ADMIN)
                .ifPresentOrElse(
                    admin -> toStage.add(buildAdminDigest(cohortId, syncJobId, admin, unattributed)),
                    () -> LOG.warn(
                        "[notification] syncJob={} {} unattributed issue(s) but no active admin found",
                        syncJobId, unattributed.size()));
        }

        if (toStage.isEmpty()) {
            return;
        }

        notificationRepository.saveAll(toStage);
        eventPublisher.publishEvent(new SyncJobNotificationsStagedEvent(syncJobId));
    }

    private List<RowIssueSummary> parseIssues(String errorReportJson) {
        if (errorReportJson == null || errorReportJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(errorReportJson, new TypeReference<List<RowIssueSummary>>() { });
        } catch (JsonProcessingException ex) {
            LOG.warn("[notification] could not parse stored errorReportJson: {}", ex.getMessage());
            return List.of();
        }
    }

    private Notification buildInstructorDigest(UUID cohortId, UUID syncJobId, InstructorContact instructor,
                                               List<RowIssueSummary> issues, boolean autoSend) {
        return Notification.builder()
            .cohortId(cohortId)
            .syncJobId(syncJobId)
            .type("instructor_digest")
            .recipientKind("instructor")
            .recipientInstructorId(instructor.getId())
            .dispatchPolicy(autoSend ? "AUTO" : "HELD")
            .subject("Grading corrections needed — " + issues.size() + " row(s)")
            .body(renderDigestBody(issues))
            .payloadJson(writeIssuesJson(issues))
            .status("PENDING")
            .build();
    }

    private Notification buildAdminDigest(UUID cohortId, UUID syncJobId, User admin, List<RowIssueSummary> issues) {
        return Notification.builder()
            .cohortId(cohortId)
            .syncJobId(syncJobId)
            .type("admin_run_digest")
            .recipientKind("admin")
            .recipientUserId(admin.getId())
            .dispatchPolicy("AUTO")
            .subject("Unresolved reviewer — " + issues.size() + " row(s) need attention")
            .body(renderDigestBody(issues))
            .payloadJson(writeIssuesJson(issues))
            .status("PENDING")
            .build();
    }

    private String writeIssuesJson(List<RowIssueSummary> issues) {
        try {
            return objectMapper.writeValueAsString(issues);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    // Kept as a plain content fragment, not pre-wrapped in the full email template — the template
    // wrap happens at dispatch time so it stays re-renderable on a later manual retry.
    private String renderDigestBody(List<RowIssueSummary> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table style=\"width:100%;border-collapse:collapse;\">");
        sb.append("<tr><th>File</th><th>Location</th><th>Rule</th><th>Message</th></tr>");
        for (RowIssueSummary issue : issues) {
            sb.append("<tr>")
                .append("<td>").append(escape(issue.file())).append("</td>")
                .append("<td>").append(escape(issue.location())).append("</td>")
                .append("<td>").append(escape(issue.rule())).append("</td>")
                .append("<td>").append(escape(issue.message())).append("</td>")
                .append("</tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}