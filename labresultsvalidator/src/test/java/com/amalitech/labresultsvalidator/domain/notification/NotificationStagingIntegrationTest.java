package com.amalitech.labresultsvalidator.domain.notification;

import com.amalitech.labresultsvalidator.domain.notification.entity.Notification;
import com.amalitech.labresultsvalidator.domain.notification.repository.NotificationRepository;
import com.amalitech.labresultsvalidator.domain.sync.service.CohortSyncService;
import com.amalitech.labresultsvalidator.support.AbstractIntegrationTest;
import com.amalitech.labresultsvalidator.support.CohortFixture;
import com.amalitech.labresultsvalidator.support.GradingWorkbookBuilder;
import com.amalitech.labresultsvalidator.support.TestMailServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Epic C's staging half — C1, C3, C4 — driven by a <em>real ingestion run</em>: a workbook on the
 * fixture drive, discovered, downloaded, parsed with POI, validated against reference data in
 * Postgres, committed, and only then staged into the outbox.
 *
 * <p>This is the first test in the codebase to exercise that whole path end to end, and it is
 * main path #2 on the QA plan. Everything before it stopped at a unit boundary or needed a live
 * SharePoint tenant.
 *
 * <p>Staging is asserted on what the run actually produced rather than on hand-built intermediate
 * state. That distinction is the point: a digest that claims two rejections is only worth anything
 * if two rows were really rejected by the real validator.
 */
class NotificationStagingIntegrationTest extends AbstractIntegrationTest {

    private static final String LAB = "Provisioning a Virtual Network";
    private static final String SECOND_LAB = "Recipe Browser App";

    // instructor_contacts is a GLOBAL table with a unique email and — since V35 — a unique
    // full_name, so reviewers cannot be shared between test methods. Reusing one name across tests
    // fails on the second insert. This is the same collision that broke the QA fixtures when
    // instructor identity moved from email to name (FND-54): three cohorts sharing one address
    // under different names. Uniqueness per test is the only safe pattern here.
    private String reviewer;
    private String reviewerEmail;
    private String otherReviewer;
    private String otherReviewerEmail;

    @Autowired
    private CohortSyncService cohortSyncService;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private CohortFixture cohort;
    private String folderName;

    @BeforeEach
    void seedCohortAndDrive() {
        TestMailServer.reset();
        folderName = "staging-" + UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        reviewer = "Ama Boateng-Mensah " + suffix;
        reviewerEmail = "ama." + suffix + "@example.test";
        otherReviewer = "Kofi Adjetey-Nartey " + suffix;
        otherReviewerEmail = "kofi." + suffix + "@example.test";

        cohort = CohortFixture.create(jdbc, CohortFixture.uniqueName("Staging Test Cohort"),
            folderName, reviewer, reviewerEmail);
        cohort.addLab(LAB);
        cohort.addLab(SECOND_LAB);
        // learners.email is globally unique too, so it needs the same per-test suffix. Names do
        // not: learner matching is scoped to the cohort, so two cohorts may hold the same person.
        cohort.addLearner("Adwoa Frimpong-Baah", "adwoa." + suffix + "@example.test");
        cohort.addLearner("Yaw Oppong-Kyei", "yaw." + suffix + "@example.test");
    }

    private Path scoresFolder() {
        return fixtureRoot().resolve(folderName).resolve("Lab Scores");
    }

    // ── C1 — the outbox ──────────────────────────────────────────────────────

    @Test
    @DisplayName("C1 AC1/AC2 — a run with rejections stages the outbox and sends nothing by itself")
    void aRunWithRejectionsStagesButDoesNotSend() {
        GradingWorkbookBuilder.sheet("Module-1")
            .row(LocalDate.of(2026, 3, 2), "Adwoa Frimpong-Baah", reviewer, LAB, 82)
            .rowWithRawScore(LocalDate.of(2026, 3, 2), "Yaw Oppong-Kyei", reviewer, LAB, "not-a-score")
            .writeTo(scoresFolder().resolve("Module 1 Grading.xlsx"));

        runSyncAndWait(cohort.cohortId);

        List<Notification> staged = notificationRepository.findAll().stream()
            .filter(n -> cohort.cohortId.equals(n.getCohortId()))
            .toList();

        assertThat(staged).isNotEmpty();
        // C1 AC2 — generation must not send. The instructor digest is HELD by default (C2 AC2), so
        // nothing instructor-facing may leave the building without an admin saying so.
        assertThat(staged)
            .filteredOn(n -> NotificationTypes.INSTRUCTOR_DIGEST.equals(n.getType()))
            .isNotEmpty()
            .allSatisfy(n -> {
                assertThat(n.getStatus()).isEqualTo("PENDING");
                assertThat(n.getDispatchPolicy()).isEqualTo("HELD");
            });
        assertThat(instructorMessagesDelivered()).isZero();
    }

    @Test
    @DisplayName("C1 AC3 / C3 AC3 — a clean run stages no instructor digest at all")
    void aCleanRunStagesNoInstructorDigest() {
        GradingWorkbookBuilder.sheet("Module-1")
            .row(LocalDate.of(2026, 3, 2), "Adwoa Frimpong-Baah", reviewer, LAB, 82)
            .row(LocalDate.of(2026, 3, 2), "Yaw Oppong-Kyei", reviewer, LAB, 91)
            .writeTo(scoresFolder().resolve("Module 1 Grading.xlsx"));

        runSyncAndWait(cohort.cohortId);

        // DEV-9: digests are corrections-only. Nothing was rejected, so the instructor hears
        // nothing — silence is the correct output, and the easiest thing to get wrong.
        assertThat(instructorDigests()).isEmpty();
        assertThat(committedResultCount()).isEqualTo(2);
    }

    // ── C3 — one digest per instructor who reviewed rows ─────────────────────

    @Test
    @DisplayName("C3 AC1/AC4 — one digest per instructor, even when their rows span several sheets")
    void oneDigestPerInstructorAcrossTheWholeRun() {
        cohort.addInstructor(otherReviewer, otherReviewerEmail);

        GradingWorkbookBuilder.sheet("Module-1")
            .rowWithRawScore(LocalDate.of(2026, 3, 2), "Adwoa Frimpong-Baah", reviewer, LAB, "bad")
            .rowWithRawScore(LocalDate.of(2026, 3, 2), "Yaw Oppong-Kyei", reviewer, LAB, "also bad")
            .writeTo(scoresFolder().resolve("Module 1 Grading.xlsx"));
        GradingWorkbookBuilder.sheet("Module-2")
            .rowWithRawScore(LocalDate.of(2026, 3, 3), "Adwoa Frimpong-Baah", reviewer, SECOND_LAB, "bad")
            .rowWithRawScore(LocalDate.of(2026, 3, 3), "Yaw Oppong-Kyei", otherReviewer, SECOND_LAB, "bad")
            .writeTo(scoresFolder().resolve("Module 2 Grading.xlsx"));

        runSyncAndWait(cohort.cohortId);

        List<Notification> digests = instructorDigests();
        // Three rejected rows belong to the first reviewer across two workbooks; one to the second.
        // The requirement is one digest each, not one per sheet and not one per row.
        assertThat(digests).hasSize(2);
        assertThat(digests).extracting(Notification::getRecipientInstructorId)
            .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("C3 AC3 — an instructor who reviewed nothing gets no digest")
    void anInstructorWithNoRowsGetsNoDigest() {
        UUID uninvolved = cohort.addInstructor(otherReviewer, otherReviewerEmail);

        GradingWorkbookBuilder.sheet("Module-1")
            .rowWithRawScore(LocalDate.of(2026, 3, 2), "Adwoa Frimpong-Baah", reviewer, LAB, "bad")
            .writeTo(scoresFolder().resolve("Module 1 Grading.xlsx"));

        runSyncAndWait(cohort.cohortId);

        assertThat(instructorDigests())
            .isNotEmpty()
            .extracting(Notification::getRecipientInstructorId)
            .doesNotContain(uninvolved);
    }

    // ── C4 — the admin digest ────────────────────────────────────────────────

    @Test
    @DisplayName("C4 AC1/AC3 — every active admin gets a run digest, and it dispatches automatically")
    void everyActiveAdminGetsAnAutomaticRunDigest() {
        GradingWorkbookBuilder.sheet("Module-1")
            .row(LocalDate.of(2026, 3, 2), "Adwoa Frimpong-Baah", reviewer, LAB, 82)
            .writeTo(scoresFolder().resolve("Module 1 Grading.xlsx"));

        runSyncAndWait(cohort.cohortId);

        List<Notification> adminDigests = notificationRepository.findAll().stream()
            .filter(n -> cohort.cohortId.equals(n.getCohortId()))
            .filter(n -> NotificationTypes.ADMIN_RUN_DIGEST.equals(n.getType()))
            .toList();

        assertThat(adminDigests).isNotEmpty();
        // AUTO is what separates the admin digest from the instructor one: admins are internal and
        // their digest goes out without moderation.
        assertThat(adminDigests).allSatisfy(n ->
            assertThat(n.getDispatchPolicy()).isEqualTo("AUTO"));
    }

    // ── the run itself — main path #2 ────────────────────────────────────────

    @Test
    @DisplayName("A clean run commits its grades, skips metadata sheets, and skips an ungraded row")
    void aCleanRunCommitsGradesAndSkipsWhatItShould() {
        GradingWorkbookBuilder.sheet("Module-1")
            .row(LocalDate.of(2026, 3, 2), "Adwoa Frimpong-Baah", reviewer, LAB, 82)
            .row(LocalDate.of(2026, 3, 2), "Yaw Oppong-Kyei", reviewer, LAB, null)  // not yet graded
            .writeTo(scoresFolder().resolve("Module 1 Grading.xlsx"));

        runSyncAndWait(cohort.cohortId);

        // One commit, and the blank-score row skipped silently: no commit and no rejection.
        assertThat(committedResultCount()).isEqualTo(1);
        Integer rejected = jdbc.queryForObject(
            "SELECT COALESCE(SUM(skipped_invalid), 0) FROM ingestion_runs WHERE cohort_id = ?",
            Integer.class, cohort.cohortId);
        assertThat(rejected).isZero();
        // The Template / How To Use / Ref sheets the builder writes were not parsed as data.
        Integer rowsRead = jdbc.queryForObject(
            "SELECT COALESCE(SUM(rows_read), 0) FROM ingestion_runs WHERE cohort_id = ?",
            Integer.class, cohort.cohortId);
        assertThat(rowsRead).isEqualTo(2);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<Notification> instructorDigests() {
        return notificationRepository.findAll().stream()
            .filter(n -> cohort.cohortId.equals(n.getCohortId()))
            .filter(n -> NotificationTypes.INSTRUCTOR_DIGEST.equals(n.getType()))
            .toList();
    }

    private long instructorMessagesDelivered() {
        return java.util.Arrays.stream(TestMailServer.received())
            .filter(message -> {
                try {
                    return java.util.Arrays.stream(message.getAllRecipients())
                        .anyMatch(a -> a.toString().equals(reviewerEmail));
                } catch (Exception ex) {
                    return false;
                }
            })
            .count();
    }

    private Integer committedResultCount() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM lab_results WHERE learner_id IN "
                + "(SELECT id FROM learners WHERE cohort_id = ?)",
            Integer.class, cohort.cohortId);
    }
}
