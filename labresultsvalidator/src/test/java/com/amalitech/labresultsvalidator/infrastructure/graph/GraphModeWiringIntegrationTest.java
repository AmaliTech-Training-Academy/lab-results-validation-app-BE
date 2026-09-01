package com.amalitech.labresultsvalidator.infrastructure.graph;

import com.amalitech.labresultsvalidator.support.IntegrationTestContainers;
import com.amalitech.labresultsvalidator.support.TestStorageConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the application the way <strong>production</strong> boots it — with the drive source left at
 * its default — and checks the real Microsoft Graph implementation is the bean that gets wired.
 *
 * <p>Every other integration test runs with {@code validata.sharepoint.source=fixtures}. That leaves
 * the branch that actually ships asserted only by reading the annotation, which is exactly the kind
 * of gap this whole exercise exists to close. If a future change broke the default — a typo in the
 * property name, a stray {@code matchIfMissing = false} — every existing test would still pass and
 * the first sign of trouble would be a deployment with no drive at all.
 *
 * <p>No credentials are needed and nothing reaches the network: building an Azure credential and a
 * {@code GraphServiceClient} is lazy, so the beans construct from placeholder values and only a real
 * API call would authenticate. This test makes no API call.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({IntegrationTestContainers.class, TestStorageConfig.class})
@TestPropertySource(properties = {
    // Deliberately NOT set to "fixtures" — this is the default path, the one that ships.
    "validata.sharepoint.source=graph",
    "validata.sharepoint.fixture.root="
})
class GraphModeWiringIntegrationTest {

    @Autowired
    private GraphDriveService graphDriveService;

    @Test
    void theRealGraphImplementationIsWiredWhenTheSourceIsNotOverridden() {
        assertThat(graphDriveService)
            .as("production must get the real SharePoint client, not the fixture stand-in")
            .isInstanceOf(MicrosoftGraphDriveService.class);
    }
}
