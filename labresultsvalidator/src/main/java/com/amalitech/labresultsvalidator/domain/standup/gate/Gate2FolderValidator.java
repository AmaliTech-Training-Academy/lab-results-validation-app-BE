package com.amalitech.labresultsvalidator.domain.standup.gate;

import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class Gate2FolderValidator {

    private static final Logger LOG = LoggerFactory.getLogger(Gate2FolderValidator.class);

    private final GraphDriveService graphDriveService;
    private final SharePointProperties sharePointProperties;

    public Gate2FolderValidator(GraphDriveService graphDriveService, SharePointProperties sharePointProperties) {
        this.graphDriveService = graphDriveService;
        this.sharePointProperties = sharePointProperties;
    }

    public Gate2Result validate(String driveId, String parentItemId) {
        List<DriveItemInfo> children;
        try {
            children = graphDriveService.listChildren(driveId, parentItemId);
        } catch (GraphAccessException ex) {
            LOG.error("[gate2] could not list children of driveId={} itemId={}: {}",
                driveId, parentItemId, ex.getMessage(), ex);
            return new Gate2Result(
                GateResult.fail(null, null, "G2-ACCESS",
                    "Cannot list contents of the cohort folder. Check Validata permissions."),
                null, null
            );
        }

        // Key by lowercase name so matching is case-insensitive.
        Map<String, DriveItemInfo> foldersByName = children.stream()
            .filter(DriveItemInfo::isFolder)
            .collect(Collectors.toMap(
                d -> d.name().toLowerCase(Locale.ROOT),
                Function.identity(),
                (a, b) -> a
            ));

        LOG.info("[gate2] folders found: {}", foldersByName.keySet());

        List<GateError> errors = new ArrayList<>();
        String refFolder = sharePointProperties.referenceFolder();
        String scoresFolder = sharePointProperties.scoresFolder();
        String refFolderKey = refFolder.toLowerCase(Locale.ROOT);
        String scoresFolderKey = scoresFolder.toLowerCase(Locale.ROOT);

        if (!foldersByName.containsKey(refFolderKey)) {
            errors.add(new GateError(null, null, "G2-MISSING-FOLDER",
                "Expected subfolder '" + refFolder + "' not found under the cohort folder. "
                    + "Found folders: " + foldersByName.keySet()));
        }
        if (!foldersByName.containsKey(scoresFolderKey)) {
            errors.add(new GateError(null, null, "G2-MISSING-FOLDER",
                "Expected subfolder '" + scoresFolder + "' not found under the cohort folder. "
                    + "Found folders: " + foldersByName.keySet()));
        }

        if (!errors.isEmpty()) {
            return new Gate2Result(GateResult.fail(errors), null, null);
        }

        String referenceFolderItemId = foldersByName.get(refFolderKey).itemId();
        String scoresFolderItemId = foldersByName.get(scoresFolderKey).itemId();
        return new Gate2Result(GateResult.pass(), referenceFolderItemId, scoresFolderItemId);
    }
}
