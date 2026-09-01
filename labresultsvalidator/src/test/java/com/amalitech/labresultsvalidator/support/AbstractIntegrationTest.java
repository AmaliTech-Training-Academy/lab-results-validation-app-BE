package com.amalitech.labresultsvalidator.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.amalitech.labresultsvalidator.domain.sync.service.CohortSyncService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class for tests that run against the real application: a real Spring context, a real
 * Postgres with all 35 migrations applied, a real Redis, and the SharePoint drive served from a
 * directory this class creates.
 *
 * <p>Extend it and the wiring is done. The one thing a subclass usually wants is
 * {@link #fixtureRoot()}, the directory standing in for the drive — write a cohort folder into it
 * and the application will find it exactly as it would find one on SharePoint.
 *
 * <p>The context is deliberately shared across every subclass: same containers, same property set,
 * so Spring caches one context for the whole suite. That makes isolation the subclass's job — a
 * test that writes to the database should clean up after itself or assert against data it created,
 * because the next class inherits whatever it leaves behind.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({IntegrationTestContainers.class, TestStorageConfig.class})
public abstract class AbstractIntegrationTest {

    /**
     * One drive root for the whole suite, created before the context starts because the fixture
     * service validates it in its constructor. Subdirectories keep cohorts apart, so two test
     * classes cannot see each other's workbooks.
     */
    private static final Path FIXTURE_ROOT = createFixtureRoot();

    @Autowired
    protected TransactionTemplate transactionTemplate;

    @Autowired
    protected TestStorageConfig.InMemoryS3 archivedObjects;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected CohortSyncService cohortSyncService;

    /**
     * Triggers a sync and blocks until the job leaves {@code RUNNING}.
     *
     * <p>The runner is {@code @Async}, so there is no return value to assert on and no completion
     * callback — polling the job's terminal state is the only honest signal. Asserting immediately
     * after the trigger is the classic way to get a suite that passes on a fast machine and fails
     * in CI.
     */
    protected void runSyncAndWait(UUID cohortId) {
        cohortSyncService.triggerScheduledSyncForCohort(cohortId);

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            status = jdbcTemplate.query(
                "SELECT status FROM cohort_sync_jobs WHERE cohort_id = ? "
                    + "ORDER BY started_at DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, cohortId);
            if (status != null && !"RUNNING".equals(status)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the sync job", ex);
            }
        }
        throw new AssertionError("Sync job did not finish within 30s; last status=" + status);
    }

    @DynamicPropertySource
    static void testInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("validata.sharepoint.fixture.root", FIXTURE_ROOT::toString);
        // Point the real JavaMailSender at the in-process SMTP server. Bound once for the shared
        // context, which is why the port is reserved at class-init rather than per test.
        registry.add("spring.mail.host", TestMailServer::host);
        registry.add("spring.mail.port", TestMailServer::port);
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
    }

    /** The directory the application sees as the SharePoint drive. */
    protected static Path fixtureRoot() {
        return FIXTURE_ROOT;
    }

    /**
     * A private folder under the drive root, named for the calling test. Use one per test class so
     * cohorts from different classes cannot collide in a shared context.
     */
    protected static Path newCohortFolder(String name) {
        try {
            return Files.createDirectories(FIXTURE_ROOT.resolve(name));
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not create cohort folder " + name, ex);
        }
    }

    private static Path createFixtureRoot() {
        try {
            Path dir = Files.createTempDirectory("validata-fixture-drive");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not create the fixture drive root", ex);
        }
    }
}
