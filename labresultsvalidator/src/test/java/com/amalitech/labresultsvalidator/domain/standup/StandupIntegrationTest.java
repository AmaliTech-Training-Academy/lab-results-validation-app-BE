package com.amalitech.labresultsvalidator.domain.standup;

import com.amalitech.labresultsvalidator.domain.standup.dto.StandupResultDto;
import com.amalitech.labresultsvalidator.domain.standup.service.StandupPipelineService;
import com.amalitech.labresultsvalidator.support.AbstractIntegrationTest;
import com.amalitech.labresultsvalidator.support.ReferenceBundleBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Epic A — cohort stand-up through Gates 1–3 — against the fixture drive.
 *
 * <p>Epic A is the opposite problem from C, D and E. Almost none of it was {@code static}: it was
 * {@code manual-e2e}, judged by someone clicking through a live SharePoint tenant, and sixteen of
 * its rows are {@code Not-Run} because the interesting halves are the <em>failure</em> paths — an
 * unresolvable link, a missing subfolder, a misnamed reference file. Those are miserable to stage
 * against a real tenant (you have to break a real folder) and trivial against a directory.
 *
 * <p>This is main path #1. The gates run synchronously through {@code runGates123}, so unlike the
 * sync path there is nothing to poll — the result is returned.
 */
class StandupIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StandupPipelineService standupPipelineService;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Gate 1 checks the link's *format* against {@code ^https://[^/]+\.sharepoint\.com/.+} before
     * it resolves anything, so a cohort has to be configured with a full URL even against the
     * fixture drive. Bare paths fail G1-INVALID-URL and never reach the drive at all.
     */
    private static final String WEB_BASE = "https://fixtures.sharepoint.com/sites/validata";

    private UUID cohortId;
    private UUID adminId;
    private String folderName;
    private Path cohortFolder;

    @BeforeEach
    void seedDraftCohort() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        folderName = "standup-" + suffix;
        cohortFolder = fixtureRoot().resolve(folderName);

        adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, is_active) "
                + "VALUES (?, ?, 'x', 'admin', true)",
            adminId, "standup." + suffix + "@example.test");

        cohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, start_date, end_date, lifecycle_state, "
                + "sharepoint_folder_url) VALUES (?, ?, DATE '2026-01-01', DATE '2026-12-31', "
                + "'DRAFT', ?)",
            cohortId, "Standup Test Cohort " + suffix, WEB_BASE + "/" + folderName);
    }

    private ReferenceBundleBuilder validBundle() {
        return ReferenceBundleBuilder.bundle()
            .learner("Akosua Mensah-Bonsu", "akosua." + cohortId + "@example.test")
            .learner("Kwame Asante-Darko", "kwame." + cohortId + "@example.test")
            .instructor("Yaa Owusu-Ansah " + cohortId, "yaa." + cohortId + "@example.test");
    }

    private StandupResultDto runGates() {
        UUID jobId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohort_standup_jobs (id, cohort_id, status, started_at) "
            + "VALUES (?, ?, 'RUNNING', NOW())", jobId, cohortId);
        return standupPipelineService.runGates123(cohortId, jobId, adminId);
    }

    private String lifecycleState() {
        return jdbc.queryForObject(
            "SELECT lifecycle_state FROM cohorts WHERE id = ?", String.class, cohortId);
    }

    private Integer committedSpecializations() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM specializations WHERE cohort_id = ?", Integer.class, cohortId);
    }

    // ── the happy path — main path #1 ────────────────────────────────────────

    @Test
    @DisplayName("A3/A4/A5 — a well-formed folder passes all three gates")
    void aWellFormedFolderPassesEveryGate() {
        validBundle().writeTo(cohortFolder);

        StandupResultDto result = runGates();

        assertThat(result.gate1().state()).isEqualTo("PASSED");
        assertThat(result.gate2().state()).isEqualTo("PASSED");
        assertThat(result.gate3().state()).isEqualTo("PASSED");
    }

    @Test
    @DisplayName("A5 AC6 — a passing Gate 3 holds the bundle; nothing is committed without Accept")
    void gate3PassDoesNotCommitAnything() {
        validBundle().writeTo(cohortFolder);

        runGates();

        // A6 AC4 — the bundle is held pending an explicit Accept. If this ever regressed, reference
        // data would appear in a cohort nobody approved.
        assertThat(committedSpecializations()).isZero();
        assertThat(lifecycleState()).isEqualTo("DRAFT");
    }

    // ── A3 — Gate 1, the link ────────────────────────────────────────────────

    @Test
    @DisplayName("A3 AC2 — an unresolvable link fails Gate 1 and the cohort stays DRAFT")
    void anUnresolvableLinkFailsGate1() {
        jdbc.update("UPDATE cohorts SET sharepoint_folder_url = ? WHERE id = ?",
            WEB_BASE + "/no-such-folder-" + UUID.randomUUID(), cohortId);

        StandupResultDto result = runGates();

        assertThat(result.gate1().state()).isEqualTo("FAILED");
        assertThat(lifecycleState()).isEqualTo("DRAFT");
        // A3 AC4 — fail fast: no later gate runs once Gate 1 has failed.
        assertThat(result.gate2().state()).isNotEqualTo("PASSED");
        assertThat(result.gate3().state()).isNotEqualTo("PASSED");
    }

    @Test
    @DisplayName("A3 AC3 — a link pointing at a file rather than a folder fails Gate 1 clearly")
    void aLinkToAFileFailsGate1() {
        validBundle().writeTo(cohortFolder);
        String fileUrl = WEB_BASE + "/" + folderName + "/Reference Data/Specializations.xlsx";
        jdbc.update("UPDATE cohorts SET sharepoint_folder_url = ? WHERE id = ?", fileUrl, cohortId);

        StandupResultDto result = runGates();

        assertThat(result.gate1().state()).isEqualTo("FAILED");
        assertThat(result.gate1().errors()).isNotEmpty();
        assertThat(lifecycleState()).isEqualTo("DRAFT");
    }

    // ── A4 — Gate 2, the two subfolders ──────────────────────────────────────

    @Test
    @DisplayName("A4 AC2/AC3 — a missing scores subfolder fails Gate 2 and reads no reference data")
    void aMissingSubfolderFailsGate2() {
        validBundle().writeTo(cohortFolder);
        deleteRecursively(cohortFolder.resolve("Lab Scores"));

        StandupResultDto result = runGates();

        assertThat(result.gate1().state()).isEqualTo("PASSED");
        assertThat(result.gate2().state()).isEqualTo("FAILED");
        assertThat(result.gate2().errors()).isNotEmpty();
        assertThat(result.gate3().state()).isNotEqualTo("PASSED");
        assertThat(committedSpecializations()).isZero();
        assertThat(lifecycleState()).isEqualTo("DRAFT");
    }

    // ── A5 — Gate 3, the reference bundle ────────────────────────────────────

    @Test
    @DisplayName("A5 AC2 — a missing reference file fails Gate 3 and the failure names the file")
    void aMissingReferenceFileFailsGate3() {
        validBundle().withoutFile("Lab Reference.xlsx").writeTo(cohortFolder);

        StandupResultDto result = runGates();

        assertThat(result.gate2().state()).isEqualTo("PASSED");
        assertThat(result.gate3().state()).isEqualTo("FAILED");
        assertThat(result.gate3().errors()).isNotEmpty();
        // Naming the file is the difference between an actionable error and "stand-up failed".
        assertThat(result.gate3().errors().toString()).contains("Lab Reference.xlsx");
    }

    @Test
    @DisplayName("A5 AC2 — a misnamed reference file is treated as missing, by its expected name")
    void aMisnamedReferenceFileFailsGate3() {
        validBundle().renamingFile("Trainee Database.xlsx", "Trainees.xlsx").writeTo(cohortFolder);

        StandupResultDto result = runGates();

        assertThat(result.gate3().state()).isEqualTo("FAILED");
        assertThat(result.gate3().errors().toString()).contains("Trainee Database.xlsx");
    }

    @Test
    @DisplayName("A5 AC4 — a duplicate trainee email is reported with its file and row")
    void aDuplicateTraineeEmailIsReportedPrecisely() {
        String duplicate = "duplicate." + cohortId + "@example.test";
        ReferenceBundleBuilder.bundle()
            .learner("Abena Sarpong-Owusu", duplicate)
            .learner("Kofi Boadu-Antwi", duplicate)
            .instructor("Yaa Owusu-Ansah " + cohortId, "yaa." + cohortId + "@example.test")
            .writeTo(cohortFolder);

        StandupResultDto result = runGates();

        assertThat(result.gate3().state()).isEqualTo("FAILED");
        String errors = result.gate3().errors().toString();
        assertThat(errors).contains("G3-DUP-TRAINEE-EMAIL");
        assertThat(errors).contains("Trainee Database.xlsx");
        // Row-level precision: the admin has to know which line of which file to fix.
        assertThat(errors).containsPattern("row \\d+");
    }

    @Test
    @DisplayName("A5 AC3 — a learner naming an unknown specialization fails Gate 3")
    void anUnknownSpecializationFailsGate3() {
        ReferenceBundleBuilder.bundle()
            .instructor("Yaa Owusu-Ansah " + cohortId, "yaa." + cohortId + "@example.test")
            .writeTo(cohortFolder);
        // Rewrite the trainee file with a specialization that is not in the Specializations file.
        writeTraineeWithSpecialization("Nowhere Engineering");

        StandupResultDto result = runGates();

        assertThat(result.gate3().state()).isEqualTo("FAILED");
        assertThat(result.gate3().errors().toString()).contains("G3-UNKNOWN-SPEC-NAME");
    }

    @Test
    @DisplayName("A5 AC5 — any Gate 3 error commits nothing at all (atomicity)")
    void aGate3FailureCommitsNothing() {
        validBundle().withoutFile("Instructor Database.xlsx").writeTo(cohortFolder);

        runGates();

        // Specializations, modules and labs all parse cleanly in this bundle — the only fault is one
        // missing file. Atomicity means none of the good parts land either.
        assertThat(committedSpecializations()).isZero();
        Integer learners = jdbc.queryForObject(
            "SELECT count(*) FROM learners WHERE cohort_id = ?", Integer.class, cohortId);
        assertThat(learners).isZero();
        assertThat(lifecycleState()).isEqualTo("DRAFT");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void writeTraineeWithSpecialization(String specialization) {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = workbook.createSheet("Trainees");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Amalitech Email");
            header.createCell(1).setCellValue("Full Name");
            header.createCell(2).setCellValue("Specialization");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("stray." + cohortId + "@example.test");
            row.createCell(1).setCellValue("Ama Nyarko-Boateng");
            row.createCell(2).setCellValue(specialization);
            try (var out = Files.newOutputStream(
                    cohortFolder.resolve("Reference Data").resolve("Trainee Database.xlsx"))) {
                workbook.write(out);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not write the trainee file", ex);
        }
    }

    private static void deleteRecursively(Path path) {
        try (var entries = Files.walk(path)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ex) {
                    throw new UncheckedIOException("Could not delete " + p, ex);
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not delete " + path, ex);
        }
    }
}
