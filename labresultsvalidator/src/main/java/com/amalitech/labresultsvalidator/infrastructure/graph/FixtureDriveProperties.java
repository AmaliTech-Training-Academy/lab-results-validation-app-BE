package com.amalitech.labresultsvalidator.infrastructure.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link FixtureDriveService}. Kept separate from {@link SharePointProperties}
 * so the production contract record stays exactly as it is — the folder and reference-file names
 * are shared by both implementations and are none of the fake's business.
 *
 * @param root      directory that stands in for the drive root. Each immediate subdirectory is a
 *                  cohort folder, and is expected to contain the configured reference and scores
 *                  subfolders — which is the layout {@code QA_Fixtures/} already has.
 * @param siteId    site id reported for resolved items, so the Gate 1 sanctioned-site check can be
 *                  exercised in both directions
 * @param webUrlBase prefix used to synthesise a plausible {@code webUrl} for audit provenance
 */
@ConfigurationProperties(prefix = "validata.sharepoint.fixture")
public record FixtureDriveProperties(
    String root,
    String siteId,
    String webUrlBase
) {
    public String siteIdOrDefault() {
        return siteId == null || siteId.isBlank() ? "fixture-site" : siteId;
    }

    public String webUrlBaseOrDefault() {
        return webUrlBase == null || webUrlBase.isBlank()
            ? "https://fixtures.invalid/sites/validata"
            : webUrlBase;
    }
}
