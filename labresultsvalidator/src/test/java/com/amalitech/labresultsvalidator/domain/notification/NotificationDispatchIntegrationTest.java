package com.amalitech.labresultsvalidator.domain.notification;

import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationRepository;
import com.amalitech.labresultsvalidator.domain.notification.service.NotificationDispatchService;
import com.amalitech.labresultsvalidator.support.AbstractIntegrationTest;
import com.amalitech.labresultsvalidator.support.TestMailServer;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Epic C's dispatch half — C7, C8, C9, C10 — against a real database and a real SMTP server.
 *
 * <p>Every one of these criteria was previously marked {@code static} in the RTM: judged by reading
 * the code, never executed. The distinction matters most for C10 AC1 ("no notification is ever
 * addressed to a learner"), which is a claim about a *delivered message* — no amount of reading
 * proves it, and a mocked mail sender only proves a method was called.
 *
 * <p>Notifications are inserted directly rather than produced by a run: dispatch is what is under
 * test here, and coupling it to ingestion would make a dispatch failure look like a parsing one.
 * The staging side (C1, C3, C4) is a separate test built on a real run.
 */
class NotificationDispatchIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationDispatchService dispatchService;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID instructorId;
    private UUID adminId;
    private UUID cohortId;
    private UUID firstJobId;
    private UUID secondJobId;
    private static final String INSTRUCTOR_EMAIL = "instructor.dispatch@example.test";
    private static final String ADMIN_EMAIL = "admin.dispatch@example.test";
    private static final String LEARNER_EMAIL = "learner.dispatch@example.test";

    @BeforeEach
    void seedRecipientsAndClearMailbox() {
        TestMailServer.reset();
        notificationRepository.deleteAll();
        jdbc.update("DELETE FROM cohort_sync_jobs");
        jdbc.update("DELETE FROM instructor_contacts WHERE full_name LIKE '%Reviewer'");
        jdbc.update("DELETE FROM cohorts WHERE name LIKE 'Dispatch Test Cohort%'");

        adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, is_active) "
                + "VALUES (?, ?, 'x', 'admin', true) ON CONFLICT (email) DO NOTHING",
            adminId, ADMIN_EMAIL);
        adminId = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?", UUID.class, ADMIN_EMAIL);

        instructorId = UUID.randomUUID();
        // No instructor_id column: V33 dropped it when identity moved to name-based matching.
        jdbc.update("INSERT INTO instructor_contacts (id, email, full_name, is_active) "
                + "VALUES (?, ?, 'Dispatch Test Reviewer', true) "
                + "ON CONFLICT (email) DO NOTHING",
            instructorId, INSTRUCTOR_EMAIL);
        instructorId = jdbc.queryForObject(
            "SELECT id FROM instructor_contacts WHERE email = ?", UUID.class, INSTRUCTOR_EMAIL);

        // sync_job_id and both recipient columns are real foreign keys, so the rows they point at
        // have to exist. Finding that out is itself a small win: a unit test with a mocked
        // repository would happily have persisted a notification referencing nothing.
        cohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, start_date, end_date, lifecycle_state) "
                + "VALUES (?, ?, DATE '2026-01-01', DATE '2026-12-31', 'STOOD_UP')",
            cohortId, "Dispatch Test Cohort " + cohortId);
        firstJobId = newSyncJob();
        secondJobId = newSyncJob();
    }

    private UUID newSyncJob() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO cohort_sync_jobs (id, cohort_id, status, started_at) "
            + "VALUES (?, ?, 'COMPLETED', NOW())", id, cohortId);
        return id;
    }

    // ── C7 — moderation actions ──────────────────────────────────────────────

    @Test
    @DisplayName("C7 AC1 — a PENDING notification dispatches, reaches the recipient and becomes SENT")
    void pendingNotificationIsDeliveredAndMarkedSent() throws Exception {
        Notification staged = givenPendingInstructorDigest();

        Notification sent = dispatchService.sendNow(staged.getId(), adminId);

        assertThat(sent.getStatus()).isEqualTo("SENT");
        assertThat(sent.getSentAt()).isNotNull();
        assertThat(sent.getErrorDetail()).isNull();
        assertThat(sent.getSentBy()).isEqualTo(adminId);

        assertThat(TestMailServer.awaitMessages(1, 5_000)).isTrue();
        MimeMessage delivered = TestMailServer.received()[0];
        assertThat(delivered.getAllRecipients()[0].toString()).isEqualTo(INSTRUCTOR_EMAIL);
        assertThat(delivered.getSubject()).isEqualTo("Grading corrections needed");
    }

    @Test
    @DisplayName("C7 AC3 — dismissing a PENDING notification skips it and sends nothing")
    void dismissedNotificationIsNeverDelivered() {
        Notification staged = givenPendingInstructorDigest();

        Notification dismissed = dispatchService.dismiss(staged.getId(), adminId);

        assertThat(dismissed.getStatus()).isEqualTo("SKIPPED");
        assertThat(dismissed.getDismissedBy()).isEqualTo(adminId);
        assertThat(dismissed.getDismissedAt()).isNotNull();
        assertThat(TestMailServer.received()).isEmpty();
    }

    @Test
    @DisplayName("C7 AC3 — dismissing an already-sent notification is refused, not silently ignored")
    void dismissingAnAlreadySentNotificationIsRefused() {
        Notification staged = givenPendingInstructorDigest();
        dispatchService.sendNow(staged.getId(), adminId);

        assertThatThrownBy(() -> dispatchService.dismiss(staged.getId(), adminId))
            .isInstanceOf(UnprocessableEntityException.class)
            .hasMessageContaining("cannot be dismissed");
    }

    // ── C8 — status transitions ──────────────────────────────────────────────

    @Test
    @DisplayName("C8 AC2 — re-sending an already-SENT notification is a no-op, not a second email")
    void resendingASentNotificationDoesNotDeliverTwice() {
        Notification staged = givenPendingInstructorDigest();
        dispatchService.sendNow(staged.getId(), adminId);
        assertThat(TestMailServer.awaitMessages(1, 5_000)).isTrue();

        Notification again = dispatchService.sendNow(staged.getId(), adminId);

        assertThat(again.getStatus()).isEqualTo("SENT");
        // The guard is the point: a duplicate digest to an instructor is a real-world annoyance
        // and this is the only thing standing between them and one per retry.
        assertThat(TestMailServer.received()).hasSize(1);
    }

    @Test
    @DisplayName("C8 AC1 / C9 AC3 — a FAILED notification can be retried and reaches SENT")
    void aFailedNotificationCanBeRetriedToSent() {
        Notification staged = givenPendingInstructorDigest();
        staged.setStatus("FAILED");
        staged.setErrorDetail("earlier transient SMTP failure");
        notificationRepository.save(staged);

        Notification retried = dispatchService.sendNow(staged.getId(), adminId);

        assertThat(retried.getStatus()).isEqualTo("SENT");
        // The stale error must be cleared, or the UI keeps showing a failure for a delivered email.
        assertThat(retried.getErrorDetail()).isNull();
        assertThat(TestMailServer.awaitMessages(1, 5_000)).isTrue();
    }

    @Test
    @DisplayName("C8 AC3 — a new run's notifications stay distinct from a prior run's PENDING ones")
    void notificationsFromDifferentRunsRemainDistinct() {
        Notification first = notificationRepository.save(pendingDigest(firstJobId));
        Notification second = notificationRepository.save(pendingDigest(secondJobId));

        dispatchService.sendNow(second.getId(), adminId);

        assertThat(notificationRepository.findById(first.getId()).orElseThrow().getStatus())
            .isEqualTo("PENDING");
        assertThat(notificationRepository.findById(second.getId()).orElseThrow().getStatus())
            .isEqualTo("SENT");
    }

    // ── C9 — delivery failure ────────────────────────────────────────────────

    @Test
    @DisplayName("C9 AC2 — a send that fails moves to FAILED with the error kept, and delivers nothing")
    void aFailedSendIsRecordedWithItsReason() {
        // A dangling recipient id turns out to be unreachable — recipient_instructor_id is a real
        // foreign key — so the reachable failure is a bad address, which is also the realistic one:
        // instructor contacts come from a spreadsheet column nobody validates.
        UUID brokenContactId = UUID.randomUUID();
        jdbc.update("INSERT INTO instructor_contacts (id, email, full_name, is_active) "
            + "VALUES (?, 'not a valid address', 'Broken Address Reviewer', true)", brokenContactId);

        Notification staged = pendingDigest(firstJobId);
        staged.setRecipientInstructorId(brokenContactId);
        Notification saved = notificationRepository.save(staged);

        Notification result = dispatchService.sendNow(saved.getId(), adminId);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getErrorDetail()).isNotBlank();
        assertThat(TestMailServer.received()).isEmpty();
    }

    @Test
    @DisplayName("C9 AC2 — one bad recipient does not stop the rest of the batch")
    void oneFailedSendDoesNotStopTheOthers() {
        UUID brokenContactId = UUID.randomUUID();
        jdbc.update("INSERT INTO instructor_contacts (id, email, full_name, is_active) "
            + "VALUES (?, 'also not valid', 'Second Broken Reviewer', true)", brokenContactId);
        Notification broken = pendingDigest(firstJobId);
        broken.setRecipientInstructorId(brokenContactId);
        Notification failing = notificationRepository.save(broken);
        Notification healthy = givenPendingInstructorDigest();

        dispatchService.sendNow(failing.getId(), adminId);
        dispatchService.sendNow(healthy.getId(), adminId);

        assertThat(notificationRepository.findById(failing.getId()).orElseThrow().getStatus())
            .isEqualTo("FAILED");
        assertThat(notificationRepository.findById(healthy.getId()).orElseThrow().getStatus())
            .isEqualTo("SENT");
        assertThat(TestMailServer.awaitMessages(1, 5_000)).isTrue();
        assertThat(TestMailServer.received()).hasSize(1);
    }

    // ── C5 AC2 — in-app only ─────────────────────────────────────────────────

    @Test
    @DisplayName("C5 AC2 — a stood-up confirmation is raised in-app and deliberately never emailed")
    void aStoodUpConfirmationIsNeverEmailed() {
        Notification staged = notificationRepository.save(Notification.builder()
            .syncJobId(firstJobId)
            .cohortId(cohortId)
            .type(NotificationTypes.STOOD_UP)
            .recipientKind("admin")
            .recipientUserId(adminId)
            .dispatchPolicy("AUTO")
            .subject("Cohort stood up")
            .body("The cohort is ready.")
            .status("PENDING")
            .build());

        Notification result = dispatchService.sendNow(staged.getId(), adminId);

        // SKIPPED, not FAILED — "we deliberately sent nothing" is a different outcome from
        // "we tried and could not", and the Run-Review distinguishes them.
        assertThat(result.getStatus()).isEqualTo("SKIPPED");
        assertThat(result.getErrorDetail()).isNull();
        assertThat(TestMailServer.received()).isEmpty();
    }

    // ── C10 — the learner guard ──────────────────────────────────────────────

    @Test
    @DisplayName("C10 AC1 — every delivered message goes to an instructor or admin, never a learner")
    void everyDeliveredMessageGoesToAnInstructorOrAdmin() throws Exception {
        dispatchService.sendNow(givenPendingInstructorDigest().getId(), adminId);
        assertThat(TestMailServer.awaitMessages(1, 5_000)).isTrue();

        for (MimeMessage message : TestMailServer.received()) {
            for (var address : message.getAllRecipients()) {
                assertThat(address.toString())
                    .isIn(INSTRUCTOR_EMAIL, ADMIN_EMAIL)
                    .isNotEqualTo(LEARNER_EMAIL);
            }
        }
    }

    @Test
    @DisplayName("C10 AC1 — a learner recipient has no representation: the database refuses one")
    void aLearnerRecipientCannotEvenBePersisted() {
        // Stronger than checking that no learner was mailed today. chk_notif_kind means the state
        // cannot be reached at all, so no future code path can reach it either — which is what
        // "enforced regardless of data content" has to mean to be worth anything.
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO notifications (type, recipient_kind, dispatch_policy, status) "
                + "VALUES ('instructor_digest', 'learner', 'HELD', 'PENDING')"))
            .hasMessageContaining("chk_notif_kind");
    }

    // ── C7 AC4 — known gap ───────────────────────────────────────────────────

    @Test
    @Disabled("FND-53 / RTM C7-AC4 — dispatch and dismiss write no audit_event row. "
        + "The requirement is real and this test states it; enable it when the gap is closed.")
    @DisplayName("C7 AC4 — every dispatch and dismiss is written to the audit trail")
    void everyDispatchIsAudited() {
        Notification staged = givenPendingInstructorDigest();

        dispatchService.sendNow(staged.getId(), adminId);

        Integer auditRows = jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE event_type = 'NOTIFICATION_SENT'", Integer.class);
        assertThat(auditRows).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Notification givenPendingInstructorDigest() {
        return notificationRepository.save(pendingDigest(firstJobId));
    }

    private Notification pendingDigest(UUID syncJobId) {
        return Notification.builder()
            .syncJobId(syncJobId)
            // A trigger enforces that these two agree; leaving cohort_id null against a job that
            // has one is rejected outright. Worth knowing — it means a notification can never be
            // attributed to the wrong cohort, and nothing in the code comments says so.
            .cohortId(cohortId)
            .type(NotificationTypes.INSTRUCTOR_DIGEST)
            .recipientKind("instructor")
            .recipientInstructorId(instructorId)
            .dispatchPolicy("HELD")
            .subject("Grading corrections needed")
            .body("Two rows on your sheet were rejected.")
            .status("PENDING")
            .build();
    }
}
