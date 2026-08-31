package com.amalitech.labresultsvalidator.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Seeds a stood-up cohort with the reference data an ingestion run needs, and points it at a
 * folder on the fixture drive.
 *
 * <p>Reference data is inserted rather than committed through the stand-up gates on purpose. The
 * tests that use this are about what happens <em>after</em> a cohort is stood up — ingestion,
 * conflicts, notifications — and driving five reference workbooks through Gates 1–4 to reach that
 * state would mean a failure anywhere in stand-up presenting as a notification failure. Stand-up
 * gets its own tests, through the gates, where a gate failure means what it says.
 *
 * <p>The resolution chain a score row depends on is the whole reason this class exists:
 * <pre>
 *   cohort → specializations → modules → labs        (Lab Title + specialization → lab)
 *   cohort → learners                                (Name of NSP → learner)
 *   specializations → assignments → instructor_contacts   (Reviewer → instructor)
 * </pre>
 * A reviewer who is not assigned to one of <em>this</em> cohort's specializations does not resolve,
 * even if the name exists globally — which is deliberate, and easy to get wrong when seeding.
 */
public final class CohortFixture {

    public final UUID cohortId;
    public final UUID specializationId;
    public final UUID moduleId;
    public final UUID instructorId;
    public final String instructorName;
    public final String instructorEmail;

    private final JdbcTemplate jdbc;

    private CohortFixture(JdbcTemplate jdbc, UUID cohortId, UUID specializationId, UUID moduleId,
                          UUID instructorId, String instructorName, String instructorEmail) {
        this.jdbc = jdbc;
        this.cohortId = cohortId;
        this.specializationId = specializationId;
        this.moduleId = moduleId;
        this.instructorId = instructorId;
        this.instructorName = instructorName;
        this.instructorEmail = instructorEmail;
    }

    /**
     * @param folderItemId the cohort folder's id on the fixture drive — its path relative to the
     *                     drive root, e.g. {@code "Staging Test Cohort"}
     */
    public static CohortFixture create(JdbcTemplate jdbc, String name, String folderItemId,
                                       String instructorName, String instructorEmail) {
        UUID cohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, start_date, end_date, lifecycle_state, "
                + "sharepoint_folder_url, sharepoint_drive_id, sharepoint_item_id) "
                + "VALUES (?, ?, DATE '2026-01-01', DATE '2026-12-31', 'STOOD_UP', ?, ?, ?)",
            cohortId, name, "https://fixtures.invalid/sites/validata/" + folderItemId,
            "fixture-drive", folderItemId);

        UUID specializationId = UUID.randomUUID();
        jdbc.update("INSERT INTO specializations (id, cohort_id, name, code) VALUES (?, ?, ?, ?)",
            specializationId, cohortId, "Backend Engineering", "BE");

        UUID moduleId = UUID.randomUUID();
        // No sequence column: V16 dropped it.
        jdbc.update("INSERT INTO modules (id, specialization_id, name, code, status) "
            + "VALUES (?, ?, 'Module 1', 'M1', 'active')", moduleId, specializationId);

        UUID instructorId = UUID.randomUUID();
        jdbc.update("INSERT INTO instructor_contacts (id, email, full_name, is_active) "
            + "VALUES (?, ?, ?, true)", instructorId, instructorEmail, instructorName);
        // Without this row the reviewer will not resolve, however correct the name is.
        jdbc.update("INSERT INTO instructor_specialization_assignments "
                + "(instructor_contact_id, specialization_id) VALUES (?, ?)",
            instructorId, specializationId);

        return new CohortFixture(jdbc, cohortId, specializationId, moduleId,
            instructorId, instructorName, instructorEmail);
    }

    public UUID addLab(String title) {
        UUID labId = UUID.randomUUID();
        jdbc.update("INSERT INTO labs (id, module_id, title, max_score) VALUES (?, ?, ?, 100)",
            labId, moduleId, title);
        return labId;
    }

    public UUID addLearner(String fullName, String email) {
        UUID learnerId = UUID.randomUUID();
        // No learner_id column: V33 dropped it alongside instructor_contacts.instructor_id.
        jdbc.update("INSERT INTO learners (id, full_name, email, cohort_id, specialization_id, status) "
                + "VALUES (?, ?, ?, ?, ?, 'active')",
            learnerId, fullName, email, cohortId, specializationId);
        return learnerId;
    }

    /** A second instructor on the same cohort, for tests about per-instructor digests. */
    public UUID addInstructor(String fullName, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO instructor_contacts (id, email, full_name, is_active) "
            + "VALUES (?, ?, ?, true)", id, email, fullName);
        jdbc.update("INSERT INTO instructor_specialization_assignments "
                + "(instructor_contact_id, specialization_id) VALUES (?, ?)",
            id, specializationId);
        return id;
    }

    /**
     * <strong>There is no cleanup, by design.</strong> {@code ingestion_runs} is append-only — a
     * trigger blocks DELETE (V23) — and its {@code cohort_id} is {@code ON DELETE RESTRICT}, so a
     * cohort that has ever had a run cannot be removed through the database at all. That is the
     * audit guarantee working, not an obstacle to route around.
     *
     * <p>So isolation comes from uniqueness instead: every fixture gets its own cohort with a
     * random name, and tests assert against ids they created rather than global counts. Rows
     * accumulate across the suite, which is harmless in a throwaway container and much less
     * fragile than trying to unpick a dependency graph the schema is designed to protect.
     */
    public static String uniqueName(String prefix) {
        return prefix + " " + UUID.randomUUID();
    }
}
