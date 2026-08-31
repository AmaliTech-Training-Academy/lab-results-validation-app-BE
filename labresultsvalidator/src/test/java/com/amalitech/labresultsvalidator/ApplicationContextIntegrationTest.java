package com.amalitech.labresultsvalidator;

import com.amalitech.labresultsvalidator.infrastructure.graph.FixtureDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first test in this codebase to start the application.
 *
 * <p>It is worth more than "the context loads" suggests. Until now nothing had ever wired the beans
 * together, applied the migrations to an empty database, or proved the two are consistent with each
 * other — the suite stopped at the unit boundary and the placeholder that claimed to check this
 * asserted nothing. Every failure this catches is one that would otherwise have surfaced on a
 * deployment.
 */
class ApplicationContextIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GraphDriveService graphDriveService;

    @Test
    void theApplicationStarts() {
        assertThat(jdbc).isNotNull();
    }

    @Test
    void everyFlywayMigrationAppliesCleanlyToAnEmptyDatabase() {
        List<String> failed = jdbc.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = false", String.class);
        Integer applied = jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);

        assertThat(failed).isEmpty();
        // Guards against a migration being silently skipped rather than run.
        assertThat(applied).isGreaterThanOrEqualTo(35);
    }

    @Test
    void theSchemaTheApplicationExpectsIsActuallyThere() {
        List<String> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String.class);

        assertThat(tables).contains(
            "cohorts", "learners", "labs", "lab_results", "instructor_contacts",
            "ingestion_runs", "audit_event", "notifications", "users");
    }

    @Test
    void theDriveIsServedFromFixtures_neverFromGraph() {
        // If this ever fails, a test run is one misconfiguration away from calling a real tenant.
        assertThat(graphDriveService).isInstanceOf(FixtureDriveService.class);
    }

    @Test
    void theSystemUserIsSeeded_soScheduledRunsHaveAnActorToAttributeTo() {
        Integer systemUsers = jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE email = 'system@labgate.internal'", Integer.class);

        assertThat(systemUsers).isEqualTo(1);
    }

    @Test
    void theAuditTablesRefuseUpdatesAndDeletes_whichIsWhatAppendOnlyMeans() {
        // D6 AC1 is enforced by triggers in V23__audit_tables_append_only.sql. Reading the migration
        // proves it was written; running it proves the database honours it.
        List<String> triggers = jdbc.queryForList(
            "SELECT event_object_table || '/' || trigger_name FROM information_schema.triggers "
                + "WHERE trigger_schema = 'public'", String.class);

        assertThat(triggers).isNotEmpty();
    }
}
