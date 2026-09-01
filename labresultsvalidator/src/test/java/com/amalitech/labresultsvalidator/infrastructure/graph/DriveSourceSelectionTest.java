package com.amalitech.labresultsvalidator.infrastructure.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which drive implementation gets wired, for every value of
 * {@code validata.sharepoint.source} — including the values nobody intends.
 *
 * <p>The switch is two mutually exclusive conditions, so the question worth answering is not "does
 * the happy path work" but "what happens when it is set wrong". A misconfigured deployment must
 * stop at boot, not start up quietly reading the wrong drive. These run without a database or a
 * container, so they are cheap enough to keep as a permanent guard on the wiring.
 */
class DriveSourceSelectionTest {

    @TempDir
    Path fixtureRoot;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            // The two implementations are @Service beans, so they are only candidates here if
            // registered explicitly — the runner does not component-scan. Their @Conditional
            // annotations are still honoured, which is the thing under test.
            .withUserConfiguration(GraphConfig.class, GraphRetryExecutor.class,
                MicrosoftGraphDriveService.class, FixtureDriveService.class)
            .withBean(SharePointProperties.class, () -> new SharePointProperties(
                "Reference Data", "Lab Scores",
                new SharePointProperties.RefFiles("s", "m", "l", "t", "i"), 1024L))
            .withPropertyValues(
                "azure.graph.tenant-id=placeholder",
                "azure.graph.client-id=placeholder",
                "azure.graph.client-secret=placeholder",
                "azure.graph.sanctioned-site-id=fixture-site",
                "validata.sharepoint.fixture.site-id=fixture-site",
                "validata.graph.retry.max-attempts=1",
                "validata.graph.retry.initial-backoff-millis=0",
                "validata.graph.retry.max-backoff-millis=0",
                "validata.graph.retry.max-retry-after-millis=0");
    }

    @Test
    void theRealClientIsWiredWhenTheSourceIsNotSetAtAll() {
        // The production case: nobody has heard of this property. matchIfMissing = true is the whole
        // safety argument for the change, so it is worth an explicit test rather than a reading.
        runner().run(context -> assertThat(context)
            .hasSingleBean(MicrosoftGraphDriveService.class)
            .doesNotHaveBean(FixtureDriveService.class));
    }

    @Test
    void theRealClientIsWiredWhenTheSourceSaysGraph() {
        runner().withPropertyValues("validata.sharepoint.source=graph")
            .run(context -> assertThat(context).hasSingleBean(MicrosoftGraphDriveService.class));
    }

    @Test
    void theSourceValueIsCaseInsensitive_soGRAPHisStillTheRealClient() {
        // Spring compares the value case-insensitively. Worth pinning: an operator who writes
        // "Graph" in a deployment config should not silently get no drive.
        runner().withPropertyValues("validata.sharepoint.source=GRAPH")
            .run(context -> assertThat(context).hasSingleBean(MicrosoftGraphDriveService.class));
    }

    @Test
    void theFixtureDriveIsWiredOnlyWhenExplicitlyAskedFor() {
        runner().withPropertyValues(
                "validata.sharepoint.source=fixtures",
                "validata.sharepoint.fixture.root=" + fixtureRoot)
            .run(context -> assertThat(context)
                .hasSingleBean(FixtureDriveService.class)
                .doesNotHaveBean(MicrosoftGraphDriveService.class));
    }

    @Test
    void anUnrecognisedSourceLeavesNoDriveAtAll_whichStopsTheApplicationLoudly() {
        // A typo matches neither condition, so no GraphDriveService exists and anything that injects
        // one fails to start. Loud beats quiet: the alternative would be an app that boots and reads
        // nothing.
        runner().withPropertyValues("validata.sharepoint.source=sharepiont")
            .run(context -> assertThat(context)
                .doesNotHaveBean(MicrosoftGraphDriveService.class)
                .doesNotHaveBean(FixtureDriveService.class)
                .doesNotHaveBean(GraphDriveService.class));
    }

    @Test
    void fixtureModeWithNoRootRefusesToStart() {
        // Without the guard this would resolve to the working directory and serve it as the drive.
        runner().withPropertyValues("validata.sharepoint.source=fixtures")
            .run(context -> assertThat(context).hasFailed());
    }
}
