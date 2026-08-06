package com.amalitech.labresultsvalidator.domain.notification.service;

import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.notification.NotificationTypes;
import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationRepository;
import com.amalitech.labresultsvalidator.domain.standup.gate.GateError;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The immediate-action alerts of C5 — one typed method per condition, so the pipeline runners stay
 * free of subject lines and HTML.
 *
 * <p>Unlike {@link NotificationStagingService}, these are not batched behind
 * {@code SyncJobNotificationsStagedEvent}: C5 AC1 requires them dispatched <em>immediately</em>
 * rather than held for the run digest. Every caller is an {@code @Async} pipeline runner with no
 * ambient transaction, so each save commits on its own and dispatch can follow directly.
 *
 * <p>Every method is best-effort. A notification must never be able to change the outcome of the gate
 * or sync that triggered it, so failures are logged and swallowed here as well as by callers.
 */
@Service
@RequiredArgsConstructor
public class NotificationAlertService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationAlertService.class);

    /** Enough to convey the shape of a failure without pasting a whole error report into an email. */
    private static final int MAX_ERRORS_LISTED = 20;

    private final NotificationRepository notificationRepository;
    private final NotificationDispatchService dispatchService;
    private final UserRepository userRepository;
    private final CohortRepository cohortRepository;
    private final ObjectMapper objectMapper;

    /** C5 AC1 — a stand-up gate failure. One alert per run, naming the gate that actually stopped it. */
    public void alertStandupFailure(UUID cohortId, String gateLabel, List<GateError> errors) {
        String cohortName = cohortName(cohortId);
        List<GateError> safeErrors = errors == null ? List.of() : errors;

        String body = renderAlert(
            "Stand-up failed — " + gateLabel,
            "Cohort '" + cohortName + "' did not pass " + gateLabel + ", so it has not been stood up. "
                + "Fix the issues below in SharePoint and re-run stand-up.",
            details("Cohort", cohortName, "Stage", gateLabel, "Issues", String.valueOf(safeErrors.size())),
            safeErrors.stream()
                .limit(MAX_ERRORS_LISTED)
                .map(error -> text(error.file()) + " " + text(error.location())
                    + " — " + text(error.rule()) + ": " + text(error.message()))
                .toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cohortName", cohortName);
        payload.put("stage", gateLabel);
        payload.put("errorCount", safeErrors.size());

        stageForAllAdmins(NotificationTypes.STANDUP_FAILURE, cohortId, null, null,
            "Stand-up failed — " + cohortName + " (" + gateLabel + ")", body, payload);
    }

    /** C5 AC1 — duplicates queued for resolution. One alert per run, however many files produced them. */
    public void alertConflictsPending(UUID cohortId, UUID syncJobId, int conflictCount) {
        String cohortName = cohortName(cohortId);
        String body = renderAlert(
            "Conflicts awaiting resolution",
            "This run queued " + conflictCount + " duplicate row(s) that need a decision before their "
                + "scores are applied.",
            details("Cohort", cohortName, "Conflicts", String.valueOf(conflictCount)),
            List.of());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cohortName", cohortName);
        payload.put("conflictCount", conflictCount);

        stageForAllAdmins(NotificationTypes.CONFLICT_ALERT, cohortId, syncJobId, null,
            conflictCount + " conflict(s) awaiting resolution — " + cohortName, body, payload);
    }

    /**
     * C5 AC2 — an in-app confirmation only. Staged {@code AUTO} like the rest; it is
     * {@link NotificationDispatchService} that recognises the type as in-app and sends no email.
     */
    public void confirmStoodUp(UUID cohortId) {
        String cohortName = cohortName(cohortId);
        String body = renderAlert(
            "Cohort stood up",
            "Cohort '" + cohortName + "' passed every gate and is now stood up. Weekly grading sync "
                + "will pick it up.",
            details("Cohort", cohortName),
            List.of());

        stageForAllAdmins(NotificationTypes.STOOD_UP, cohortId, null, null,
            "Cohort stood up — " + cohortName, body, Map.of("cohortName", cohortName));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Stages one notification per active admin (C5 AC1 says "to admins") and dispatches each
     * immediately. One recipient failing is logged and skipped so the others still get theirs.
     */
    private void stageForAllAdmins(String type, UUID cohortId, UUID syncJobId, UUID ingestionRunId,
                                   String subject, String body, Map<String, Object> payload) {
        List<User> admins = userRepository.findAllByRoleAndIsActiveTrue(UserRole.ADMIN);
        if (admins.isEmpty()) {
            LOG.warn("[notification] no active admin to receive {} for cohort {}", type, cohortId);
            return;
        }

        String payloadJson = writeJson(payload);
        for (User admin : admins) {
            try {
                Notification saved = notificationRepository.save(Notification.builder()
                    .cohortId(cohortId)
                    .syncJobId(syncJobId)
                    .ingestionRunId(ingestionRunId)
                    .type(type)
                    .recipientKind("admin")
                    .recipientUserId(admin.getId())
                    // C5 AC1 — auto, never held for the digest.
                    .dispatchPolicy("AUTO")
                    .subject(subject)
                    .body(body)
                    .payloadJson(payloadJson)
                    .status("PENDING")
                    .build());

                dispatchService.sendAsync(saved.getId(), null);
            } catch (RuntimeException ex) {
                LOG.error("[notification] could not stage {} for admin {}: {}", type, admin.getId(),
                    ex.getMessage(), ex);
            }
        }
    }

    /** Label/value pairs, so every alert body reads the same way. */
    private static List<String[]> details(String... labelValuePairs) {
        List<String[]> details = new ArrayList<>();
        for (int i = 0; i + 1 < labelValuePairs.length; i += 2) {
            details.add(new String[]{labelValuePairs[i], labelValuePairs[i + 1]});
        }
        return details;
    }

    /**
     * A plain content fragment, matching {@code NotificationStagingService.renderDigestBody} — the
     * full email template is wrapped at dispatch time so the body stays re-renderable on a retry.
     */
    private String renderAlert(String title, String summary, List<String[]> detailRows,
                               List<String> bullets) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2 style=\"margin:0 0 12px;font-size:18px;color:#08283B;\">")
            .append(escape(title)).append("</h2>");
        sb.append("<p style=\"margin:0 0 16px;font-size:15px;line-height:1.6;color:#374151;\">")
            .append(escape(summary)).append("</p>");

        if (!detailRows.isEmpty()) {
            sb.append("<table style=\"width:100%;border-collapse:collapse;margin:0 0 16px;\">");
            for (String[] row : detailRows) {
                sb.append("<tr><td style=\"padding:4px 8px;color:#6B7280;\">").append(escape(row[0]))
                    .append("</td><td style=\"padding:4px 8px;font-weight:700;color:#08283B;\">")
                    .append(escape(row[1])).append("</td></tr>");
            }
            sb.append("</table>");
        }

        if (!bullets.isEmpty()) {
            sb.append("<ul style=\"margin:0 0 16px;padding-left:20px;font-size:13px;color:#374151;\">");
            for (String bullet : bullets) {
                sb.append("<li>").append(escape(bullet)).append("</li>");
            }
            sb.append("</ul>");
        }
        return sb.toString();
    }

    private String writeJson(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            LOG.warn("[notification] could not serialize alert payload: {}", ex.getMessage());
            return "{}";
        }
    }

    /**
     * Resolved here rather than passed in, so callers that only hold a cohort id (StandupJobRunner)
     * do not each need a CohortRepository. Falls back to the id if the cohort has vanished — an
     * alert with a clumsy title still beats no alert.
     */
    private String cohortName(UUID cohortId) {
        return cohortRepository.findById(cohortId)
            .map(cohort -> cohort.getName())
            .orElseGet(() -> String.valueOf(cohortId));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /** Spreadsheet and SharePoint text reaches these bodies verbatim. */
    private static String escape(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
