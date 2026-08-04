package com.amalitech.labresultsvalidator.infrastructure.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "labgate.sharepoint")
public record SharePointProperties(
    String referenceFolder,
    String scoresFolder,
    RefFiles refFiles,
    long maxWorkbookBytes
) {
    public record RefFiles(
        String specializations,
        String modules,
        String labs,
        String learners,
        String instructors
    ) {}

    // Instructors is deliberately excluded — Gate3ReferenceValidator treats it as optional
    // (missing means an empty instructor list, not a gate failure).
    public List<String> expectedRefFileNames() {
        return List.of(
            refFiles.specializations(),
            refFiles.modules(),
            refFiles.labs(),
            refFiles.learners()
        );
    }
}
