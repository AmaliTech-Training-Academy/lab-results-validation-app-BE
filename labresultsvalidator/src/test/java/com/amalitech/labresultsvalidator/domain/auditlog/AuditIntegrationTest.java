package com.amalitech.labresultsvalidator.domain.auditlog;

import com.amalitech.labresultsvalidator.support.AbstractIntegrationTest;
import com.amalitech.labresultsvalidator.support.CohortFixture;
import com.amalitech.labresultsvalidator.support.GradingWorkbookBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Epic D — audit and version history — asserted against what a real run actually wrote.
 *
 * <p>Eighteen of Epic D's nineteen rows were {@code static}. That is a poor fit for this epic in
 * particular: D5 AC4 says the audit tables are part of the dataset the data-engineering team reads
 * directly, so their contents are a published interface, not an implementation detail. Reading the
 * code proves the insert statement exists; only running it proves the row lands with the columns
 * populated.
 *
 * <p>The change-detection tests here are also main path #4. They work because the fixture drive is
 * content-addressed: syncing the same bytes twice really is unchanged, and editing the file really
 * does produce a new version — with none of SharePoint's version-publishing lag (ENV-2) in the way.
 */
class AuditIntegrationTest extends AbstractIntegrationTest {

    private static final String LAB = "Provisioning a Virtual Network";

    @Autowired
    private JdbcTemplate jdbc;

    private CohortFixture cohort;
    private String folderName;
    private String learnerName;
    private String reviewer;

    @BeforeEach
    void seedCohort() {
        folderName = "audit-" + UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        reviewer = "Efua Danso-Mensah " + suffix;
        learnerName = "Kojo Amankwah-Ofori";

        cohort = CohortFixture.create(jdbc, CohortFixture.uniqueName("Audit Test Cohort"),
            folderName, reviewer, "efua." + suffix + "@example.test");
        cohort.addLab(LAB);
        cohort.addLearner(learnerName, "kojo." + suffix + "@example.test");
    }

    private Path workbookPath() {
        return fixtureRoot().resolve(folderName).resolve("Lab Scores").resolve("Module 1 Grading.xlsx");
    }

    private void writeWorkbook(int score) {
        GradingWorkbookBuilder.sheet("Module-1")
            .row(LocalDate.of(2026, 3, 2), learnerName, reviewer, LAB, score)
            .writeTo(workbookPath());
    }

    private List<Map<String, Object>> runsForCohort() {
        return jdbc.queryForList(
            "SELECT * FROM ingestion_runs WHERE cohort_id = ? ORDER BY run_at", cohort.cohortId);
    }

    // ── D1 / D4 — the run record and its provenance ──────────────────────────

    @Test
    @DisplayName("D1 AC1 / D4 AC1 — a run writes one record per file, carrying its SharePoint provenance")
    void everyFileGetsARunRecordWithProvenance() {
        writeWorkbook(82);

        runSyncAndWait(cohort.cohortId);

        List<Map<String, Object>> runs = runsForCohort();
        assertThat(runs).hasSize(1);
        Map<String, Object> run = runs.get(0);

        assertThat(run.get("workbook_filename")).isEqualTo("Module 1 Grading.xlsx");
        // The provenance trio D4 AC1 names. quickXorHash and the version id are what make a stored
        // grade traceable back to the exact file revision it came from.
        assertThat(run.get("sharepoint_file_url")).asString().isNotBlank();
        assertThat(run.get("sharepoint_version_id")).asString().isNotBlank();
        assertThat(run.get("quick_xor_hash")).asString().isNotBlank();
        assertThat(run.get("file_sha256")).asString().isNotBlank();
        assertThat(run.get("rows_read")).isEqualTo(1);
        assertThat(run.get("committed_new")).isEqualTo(1);
        assertThat(run.get("status")).isEqualTo("completed");
    }

    @Test
    @DisplayName("D1 AC3 — a scheduled run is attributed to SYSTEM, not left null")
    void aScheduledRunIsAttributedToTheSystemUser() {
        writeWorkbook(82);

        runSyncAndWait(cohort.cohortId);

        Map<String, Object> run = runsForCohort().get(0);
        assertThat(run.get("trigger_type")).isEqualTo("SCHEDULED");
        // Attribution to a real seeded user rather than a null — that is what makes the audit row
        // answer "who did this" for an unattended run.
        assertThat(run.get("triggered_by")).isNotNull();
        String actorEmail = jdbc.queryForObject("SELECT email FROM users WHERE id = ?",
            String.class, run.get("triggered_by"));
        assertThat(actorEmail).isEqualTo("system@labgate.internal");
    }

    // ── D1 AC2 / D4 AC2 — change detection, main path #4 ─────────────────────

    @Test
    @DisplayName("D1 AC2 / D4 AC2 — an unchanged file is skipped on the second run and still recorded")
    void anUnchangedFileIsSkippedButStillAudited() {
        writeWorkbook(82);
        runSyncAndWait(cohort.cohortId);

        // Same bytes, untouched. The hash short-circuit should recognise it without re-parsing.
        runSyncAndWait(cohort.cohortId);

        List<Map<String, Object>> runs = runsForCohort();
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).get("status")).isEqualTo("completed");
        // "We saw it and nothing changed" is recorded rather than the file simply being absent from
        // the audit — which is what makes a skipped run distinguishable from an empty one.
        assertThat(runs.get(1).get("status")).isEqualTo("skipped");
        assertThat(runs.get(1).get("quick_xor_hash")).isEqualTo(runs.get(0).get("quick_xor_hash"));

        // And nothing was committed twice.
        assertThat(committedResults()).isEqualTo(1);
    }

    @Test
    @DisplayName("D4 AC2 — editing the file produces a new version and the run processes it again")
    void anEditedFileIsDetectedAsChanged() {
        writeWorkbook(82);
        runSyncAndWait(cohort.cohortId);
        String firstHash = (String) runsForCohort().get(0).get("quick_xor_hash");

        writeWorkbook(91);   // same learner, same lab, different score
        runSyncAndWait(cohort.cohortId);

        List<Map<String, Object>> runs = runsForCohort();
        assertThat(runs).hasSize(2);
        assertThat(runs.get(1).get("status")).isNotEqualTo("skipped");
        assertThat(runs.get(1).get("quick_xor_hash")).isNotEqualTo(firstHash);
        // Row identity is (learner_id, lab_id), so the re-grade updates rather than duplicating.
        assertThat(committedResults()).isEqualTo(1);
    }

    // ── D2 — lifecycle events ────────────────────────────────────────────────

    @Test
    @DisplayName("D2 AC1 — a completed sync writes a SYNC_COMPLETED audit event for the cohort")
    void aCompletedSyncWritesItsAuditEvent() {
        writeWorkbook(82);

        runSyncAndWait(cohort.cohortId);

        List<Map<String, Object>> events = jdbc.queryForList(
            "SELECT event_type, actor_user_id, payload_json FROM audit_event WHERE cohort_id = ?",
            cohort.cohortId);

        assertThat(events).isNotEmpty();
        assertThat(events).extracting(e -> e.get("event_type")).contains("SYNC_COMPLETED");
        assertThat(events).allSatisfy(e -> assertThat(e.get("actor_user_id")).isNotNull());
    }

    // ── D6 — append-only, enforced by the database ───────────────────────────

    @Test
    @DisplayName("D6 AC1 — an audit event cannot be updated or deleted, whatever the caller intends")
    void auditEventsCannotBeAlteredOrRemoved() {
        writeWorkbook(82);
        runSyncAndWait(cohort.cohortId);

        UUID eventId = jdbc.queryForObject(
            "SELECT id FROM audit_event WHERE cohort_id = ? LIMIT 1", UUID.class, cohort.cohortId);

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE audit_event SET event_type = 'TAMPERED' WHERE id = ?", eventId))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event WHERE id = ?", eventId))
            .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("D6 AC1 — a finalized ingestion run is equally immutable")
    void finalizedRunsCannotBeAlteredOrRemoved() {
        writeWorkbook(82);
        runSyncAndWait(cohort.cohortId);

        UUID runId = jdbc.queryForObject(
            "SELECT id FROM ingestion_runs WHERE cohort_id = ? LIMIT 1", UUID.class, cohort.cohortId);

        // The two rules are worded differently on purpose: a run may still be edited while it is
        // 'processing', so an UPDATE is refused for being *finalized* rather than for being
        // append-only. Only DELETE is absolute.
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE ingestion_runs SET rows_read = 999 WHERE id = ?", runId))
            .hasMessageContaining("already finalized");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ingestion_runs WHERE id = ?", runId))
            .hasMessageContaining("append-only");
    }

    // ── D3 — prior values ────────────────────────────────────────────────────

    @Test
    @DisplayName("D3 AC1/AC3 — a changed grade records its prior value, so the change is recoverable")
    void aChangedGradeKeepsItsPriorValue() {
        writeWorkbook(82);
        runSyncAndWait(cohort.cohortId);

        writeWorkbook(91);
        runSyncAndWait(cohort.cohortId);

        // The table is generic prior-value storage: table_name + record_id + field_name, not a
        // per-entity foreign key.
        List<Map<String, Object>> history = jdbc.queryForList(
            "SELECT field_name, old_value, new_value FROM lab_reference_audit_log "
                + "WHERE table_name = 'lab_results' AND record_id IN "
                + "(SELECT id FROM lab_results WHERE learner_id IN "
                + "(SELECT id FROM learners WHERE cohort_id = ?))", cohort.cohortId);

        assertThat(history)
            .as("a re-grade must leave the old mark recoverable — D3 AC3 is the whole point of D3 AC1")
            .isNotEmpty();
        assertThat(history).anySatisfy(entry ->
            assertThat(String.valueOf(entry.get("old_value"))).contains("82"));
    }

    private Integer committedResults() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM lab_results WHERE learner_id IN "
                + "(SELECT id FROM learners WHERE cohort_id = ?)", Integer.class, cohort.cohortId);
    }
}
