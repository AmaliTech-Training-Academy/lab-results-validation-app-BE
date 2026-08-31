package com.amalitech.labresultsvalidator.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @DynamicPropertySource
    static void driveProperties(DynamicPropertyRegistry registry) {
        registry.add("validata.sharepoint.fixture.root", FIXTURE_ROOT::toString);
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
