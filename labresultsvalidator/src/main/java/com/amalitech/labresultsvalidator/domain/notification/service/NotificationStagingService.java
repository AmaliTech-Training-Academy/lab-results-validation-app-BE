package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.domain.grading.dto.RowIssueSummary;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionRun;
import com.amalitech.labresultsvalidator.domain.instructor.entity.InstructorContact;
import com.amalitech.labresultsvalidator.domain.instructor.repository.InstructorContactRepository;
import com.amalitech.labresultsvalidator.domain.grading.repository.IngestionRunRepository;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.amalitech.labresultsvalidator.domain.reference.dto.LabModuleName;
import com.amalitech.labresultsvalidator.domain.reference.repository.LabRepository;
import com.amalitech.labresultsvalidator.domain.notification.event.SyncJobNotificationsStagedEvent;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationRepository;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stages (persists as {@code PENDING}) one {@code instructor_digest} {@link Notification} per
 * instructor with rejected rows, plus one {@code admin_run_digest} per active admin — all bundled
 * across every {@link IngestionRun}/file in the whole sync job, not per file. Staging never sends
 * email; dispatch is {@code NotificationDispatchService}'s concern, triggered off
 * {@link SyncJobNotificationsStagedEvent} after this method's transaction commits.
 *
 * <p>The admin digest goes out on <em>every</em> completed run, because C4 AC1 is conditioned on the
 * run completing rather than on the run having had problems — a clean run's counts are exactly what
 * confirms it was clean.
 */
@Service
public class NotificationStagingService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationStagingService.class);

    /** Bucket for rows whose Lab Title matches no configured lab in the cohort. */
    private static final String UNKNOWN_MODULE = "Unmatched labs";

    private final IngestionRunRepository ingestionRunRepository;
    private final LabRepository labRepository;
    private final InstructorContactRepository instructorContactRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationSettingsService notificationSettingsService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final boolean formatsProvisional;

    public NotificationStagingService(
        IngestionRunRepository ingestionRunRepository,
        LabRepository labRepository,
        InstructorContactRepository instructorContactRepository,
        UserRepository userRepository,
        NotificationRepository notificationRepository,
        NotificationSettingsService notificationSettingsService,
        ApplicationEventPublisher eventPublisher,
        ObjectMapper objectMapper,
        @Value("${labgate.notification.formats-provisional:true}") boolean formatsProvisional
    ) {
        this.ingestionRunRepository = ingestionRunRepository;
        this.labRepository = labRepository;
        this.instructorContactRepository = instructorContactRepository;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.notificationSettingsService = notificationSettingsService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.formatsProvisional = formatsProvisional;
    }

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

        // C4 AC1 is "given a run completes", not "given a run had problems" — so a clean run still
        // gets its admin digest. Only the instructor digests are conditional on there being issues.
        RunTotals totals = RunTotals.from(runs);

        Map<UUID, List<RowIssueSummary>> byInstructor = allIssues.stream()
            .filter(i -> i.instructorContactId() != null)
            .collect(Collectors.groupingBy(RowIssueSummary::instructorContactId));
        List<RowIssueSummary> unattributed = allIssues.stream()
            .filter(i -> i.instructorContactId() == null)
            .toList();

        boolean autoSendInstructorEmails = notificationSettingsService.getSettings().isAutoSendInstructorEmails();
        List<Notification> toStage = new ArrayList<>();

        // C3 AC2 — module grouping and the run date. One query for the whole cohort rather than a
        // lookup per row; normalized in Java so the key matches how validation reads the same cell.
        Map<String, String> moduleByLabTitle = labRepository
            .findLabModuleNamesByCohortId(cohortId).stream()
            .collect(Collectors.toMap(
                lab -> normalizeTitle(lab.labTitle()),
                LabModuleName::moduleName,
                (a, b) -> a));
        LocalDate runDate = runs.stream()
            .map(IngestionRun::getRunAt)
            .filter(Objects::nonNull)
            .max(OffsetDateTime::compareTo)
            .map(OffsetDateTime::toLocalDate)
            .orElse(LocalDate.now());

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
                    autoSendInstructorEmails, moduleByLabTitle, runDate));
            }
        }

        // C4 AC1 — "staged for all active admins", so every active admin gets their own row rather
        // than one deterministically-chosen admin standing in for the rest.
        List<User> admins = userRepository.findAllByRoleAndIsActiveTrue(UserRole.ADMIN);
        if (admins.isEmpty()) {
            LOG.warn("[notification] syncJob={} run digest could not be staged — no active admin found",
                syncJobId);
        } else {
            for (User admin : admins) {
                toStage.add(buildAdminDigest(cohortId, syncJobId, admin, unattributed, totals));
            }
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
                                               List<RowIssueSummary> issues, boolean autoSend,
                                               Map<String, String> moduleByLabTitle, LocalDate runDate) {
        return Notification.builder()
            .cohortId(cohortId)
            .syncJobId(syncJobId)
            .type("instructor_digest")
            .recipientKind("instructor")
            .recipientInstructorId(instructor.getId())
            .dispatchPolicy(autoSend ? "AUTO" : "HELD")
            .subject("Grading corrections needed — " + issues.size() + " row(s)")
            .body(renderInstructorDigestBody(issues, moduleByLabTitle, runDate))
            .payloadJson(writeIssuesJson(issues))
            .status("PENDING")
            .build();
    }

    /**
     * C3 AC2 — the run date, then per-module sections, each with its rejected-row count and the rows
     * themselves (location, rule, message) phrased for correction.
     *
     * <p>Only rejected rows can be reported per module: the digest is built from
     * {@code errorReportJson}, which by definition holds nothing about rows that succeeded. Accepted
     * and updated counts per module are not available without persisting per-row outcomes.
     */
    private String renderInstructorDigestBody(List<RowIssueSummary> issues,
                                              Map<String, String> moduleByLabTitle, LocalDate runDate) {
        Map<String, List<RowIssueSummary>> byModule = new LinkedHashMap<>();
        for (RowIssueSummary issue : issues) {
            String module = moduleByLabTitle.getOrDefault(normalizeTitle(issue.labTitle()), UNKNOWN_MODULE);
            byModule.computeIfAbsent(module, key -> new ArrayList<>()).add(issue);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(provisionalBanner());
        sb.append("<p style=\"margin:0 0 16px;font-size:15px;color:#374151;\">")
            .append("Run of ").append(escape(runDate.toString())).append(" — ")
            .append(issues.size()).append(" row(s) of yours were rejected and need correcting.</p>");

        for (Map.Entry<String, List<RowIssueSummary>> entry : byModule.entrySet()) {
            sb.append("<h3 style=\"margin:16px 0 4px;font-size:15px;color:#08283B;\">")
                .append(escape(entry.getKey())).append("</h3>");
            sb.append("<p style=\"margin:0 0 8px;font-size:13px;color:#6B7280;\">Rejected: ")
                .append(entry.getValue().size()).append("</p>");
            sb.append(renderIssueTable(entry.getValue()));
        }

        sb.append("<p style=\"margin:16px 0 0;font-size:13px;color:#6B7280;\">")
            .append("To correct a row: fix the flagged cell in the score sheet. The next sync picks the ")
            .append("corrected row up automatically — nothing else is needed.</p>");
        return sb.toString();
    }

    /** Matches how {@code ScoreRowValidationService} reads the same cell, so keys cannot drift apart. */
    private static String normalizeTitle(String rawTitle) {
        return rawTitle == null ? "" : rawTitle.trim().toLowerCase(Locale.ROOT);
    }

    private Notification buildAdminDigest(UUID cohortId, UUID syncJobId, User admin,
                                          List<RowIssueSummary> issues, RunTotals totals) {
        return Notification.builder()
            .cohortId(cohortId)
            .syncJobId(syncJobId)
            .type("admin_run_digest")
            .recipientKind("admin")
            .recipientUserId(admin.getId())
            // C4 AC3 — internal, so no moderation.
            .dispatchPolicy("AUTO")
            .subject("LabGate run summary — " + totals.filesProcessed() + " file(s), "
                + totals.rowsRead() + " row(s) read")
            .body(renderAdminDigestBody(issues, totals))
            .payloadJson(writeAdminPayloadJson(issues, totals))
            .status("PENDING")
            .build();
    }

    /**
     * C4 AC1's six counts, plus the high-failure roll-up. Summed from the run's persisted
     * {@code ingestion_runs} rows, so the digest reports the same numbers as the sync overview API.
     */
    private record RunTotals(
        int filesProcessed,
        int rowsRead,
        int committedNew,
        int updatedCount,
        int skippedInvalid,
        int skippedUnchanged,
        int conflictsCount,
        List<String> highFailureFiles
    ) {

        private static RunTotals from(List<IngestionRun> runs) {
            int rowsRead = 0;
            int committedNew = 0;
            int updatedCount = 0;
            int skippedInvalid = 0;
            int skippedUnchanged = 0;
            int conflictsCount = 0;
            List<String> highFailureFiles = new ArrayList<>();

            for (IngestionRun run : runs) {
                rowsRead += run.getRowsRead();
                committedNew += run.getCommittedNew();
                updatedCount += run.getUpdatedCount();
                skippedInvalid += run.getSkippedInvalid();
                skippedUnchanged += run.getSkippedUnchanged();
                conflictsCount += run.getConflictsCount();
                if (run.isHighFailureRate()) {
                    highFailureFiles.add(run.getWorkbookFilename());
                }
            }
            return new RunTotals(runs.size(), rowsRead, committedNew, updatedCount, skippedInvalid,
                skippedUnchanged, conflictsCount, List.copyOf(highFailureFiles));
        }
    }

    private String writeIssuesJson(List<RowIssueSummary> issues) {
        try {
            return objectMapper.writeValueAsString(issues);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    /**
     * The admin payload keeps the issue list under {@code issues} so {@code NotificationResponse}
     * still parses it, and adds the run counts alongside.
     */
    private String writeAdminPayloadJson(List<RowIssueSummary> issues, RunTotals totals) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("filesProcessed", totals.filesProcessed());
        payload.put("rowsRead", totals.rowsRead());
        payload.put("committedNew", totals.committedNew());
        payload.put("updatedCount", totals.updatedCount());
        payload.put("skippedInvalid", totals.skippedInvalid());
        payload.put("skippedUnchanged", totals.skippedUnchanged());
        payload.put("conflictsCount", totals.conflictsCount());
        payload.put("highFailureFiles", totals.highFailureFiles());
        payload.put("unresolvedReviewerRows", issues.size());
        payload.put("issues", issues);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            LOG.warn("[notification] could not serialize admin digest payload: {}", ex.getMessage());
            return "{}";
        }
    }

    /**
     * C4 AC1 — the six counts and any high-failure files, then the unresolved-reviewer rows that
     * could not reach an instructor (AC2: rolled in here, not sent separately).
     */
    private String renderAdminDigestBody(List<RowIssueSummary> issues, RunTotals totals) {
        StringBuilder sb = new StringBuilder();
        sb.append(provisionalBanner());

        sb.append("<table style=\"width:100%;border-collapse:collapse;margin:0 0 16px;\">");
        appendCount(sb, "Files processed", totals.filesProcessed());
        appendCount(sb, "Rows read", totals.rowsRead());
        appendCount(sb, "New", totals.committedNew());
        appendCount(sb, "Updated (re-grades)", totals.updatedCount());
        appendCount(sb, "Skipped — invalid", totals.skippedInvalid());
        appendCount(sb, "Skipped — unchanged", totals.skippedUnchanged());
        appendCount(sb, "Conflicts awaiting resolution", totals.conflictsCount());
        sb.append("</table>");

        if (!totals.highFailureFiles().isEmpty()) {
            sb.append("<h3 style=\"margin:16px 0 8px;font-size:15px;color:#08283B;\">")
                .append("Files flagged high-failure</h3>");
            sb.append("<ul style=\"margin:0 0 16px;padding-left:20px;font-size:13px;color:#374151;\">");
            for (String fileName : totals.highFailureFiles()) {
                sb.append("<li>").append(escape(fileName)).append("</li>");
            }
            sb.append("</ul>");
        }

        if (issues.isEmpty()) {
            sb.append("<p style=\"margin:0;font-size:13px;color:#6B7280;\">")
                .append("Every rejected row was attributed to a reviewer, so nothing needs your ")
                .append("attention here.</p>");
        } else {
            sb.append("<h3 style=\"margin:16px 0 8px;font-size:15px;color:#08283B;\">")
                .append("Rows with an unresolved reviewer</h3>");
            sb.append("<p style=\"margin:0 0 8px;font-size:13px;color:#6B7280;\">")
                .append("These could not be included in any instructor digest.</p>");
            sb.append(renderIssueTable(issues));
        }
        return sb.toString();
    }

    private void appendCount(StringBuilder sb, String label, int value) {
        sb.append("<tr><td style=\"padding:4px 8px;color:#6B7280;\">").append(escape(label))
            .append("</td><td style=\"padding:4px 8px;font-weight:700;color:#08283B;\">")
            .append(value).append("</td></tr>");
    }

    /**
     * C11 AC1 — until the PO signs the formats off (Decision Log Q3), every digest says so at the
     * top. Flipping {@code labgate.notification.formats-provisional} to false is the only change
     * sign-off requires.
     */
    private String provisionalBanner() {
        if (!formatsProvisional) {
            return "";
        }
        return "<table style=\"width:100%;border-collapse:collapse;margin:0 0 16px;"
            + "background-color:#FEF3C7;\"><tr><td style=\"padding:12px 16px;"
            + "border-left:4px solid #D97706;\">"
            + "<p style=\"margin:0;font-size:12px;font-weight:700;color:#92400E;\">"
            + "PROVISIONAL FORMAT</p>"
            + "<p style=\"margin:4px 0 0;font-size:13px;color:#92400E;\">"
            + "This layout is a draft pending sign-off (Decision Log Q3) and may change.</p>"
            + "</td></tr></table>";
    }

    // Kept as a plain content fragment, not pre-wrapped in the full email template — the template
    // wrap happens at dispatch time so it stays re-renderable on a later manual retry.
    private String renderIssueTable(List<RowIssueSummary> issues) {
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