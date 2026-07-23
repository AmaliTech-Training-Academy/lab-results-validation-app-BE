package com.amalitech.labresultsvalidator.domain.cohort.gate;

import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class Gate2FolderValidator {

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
            return new Gate2Result(
                GateResult.fail(null, null, "G2-ACCESS",
                    "Cannot list contents of the cohort folder. Check LabGate permissions."),
                null, null
            );
        }

        Map<String, DriveItemInfo> foldersByName = children.stream()
            .filter(DriveItemInfo::isFolder)
            .collect(Collectors.toMap(DriveItemInfo::name, Function.identity()));

        List<GateError> errors = new ArrayList<>();
        String refFolder = sharePointProperties.referenceFolder();
        String scoresFolder = sharePointProperties.scoresFolder();

        if (!foldersByName.containsKey(refFolder)) {
            errors.add(new GateError(null, null, "G2-MISSING-FOLDER",
                "Expected subfolder '" + refFolder + "' not found under the cohort folder."));
        }
        if (!foldersByName.containsKey(scoresFolder)) {
            errors.add(new GateError(null, null, "G2-MISSING-FOLDER",
                "Expected subfolder '" + scoresFolder + "' not found under the cohort folder."));
        }

        if (!errors.isEmpty()) {
            return new Gate2Result(GateResult.fail(errors), null, null);
        }

        String referenceFolderItemId = foldersByName.get(refFolder).itemId();
        String scoresFolderItemId = foldersByName.get(scoresFolder).itemId();
        return new Gate2Result(GateResult.pass(), referenceFolderItemId, scoresFolderItemId);
    }
}
