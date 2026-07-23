package com.amalitech.labresultsvalidator.domain.cohort.gate;

import com.amalitech.labresultsvalidator.infrastructure.graph.AzureGraphProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphItemTypeException;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphSiteViolationException;
import org.springframework.stereotype.Component;

@Component
public class Gate1LinkValidator {

    private final GraphDriveService graphDriveService;

    public Gate1LinkValidator(GraphDriveService graphDriveService, AzureGraphProperties azureGraphProperties) {
        this.graphDriveService = graphDriveService;
    }

    public Gate1Result validate(String sharepointUrl) {
        if (sharepointUrl == null || sharepointUrl.isBlank()) {
            return new Gate1Result(
                GateResult.fail("link", null, "G1-BLANK", "SharePoint folder link is required."),
                null
            );
        }

        DriveItemInfo info;
        try {
            info = graphDriveService.resolveFolder(sharepointUrl);
        } catch (GraphAccessException ex) {
            return new Gate1Result(
                GateResult.fail("link", null, "G1-ACCESS",
                    "Cannot access the SharePoint folder at " + sharepointUrl
                        + ". Check the path and that LabGate has been granted access."),
                null
            );
        } catch (GraphItemTypeException ex) {
            return new Gate1Result(
                GateResult.fail("link", null, "G1-NOT-FOLDER",
                    "The link resolves to a file, not a folder. Provide a link to a SharePoint folder."),
                null
            );
        } catch (GraphSiteViolationException ex) {
            return new Gate1Result(
                GateResult.fail("link", null, "G1-SITE-VIOLATION",
                    "The link points to a location outside the sanctioned SharePoint site."),
                null
            );
        }

        return new Gate1Result(GateResult.pass(), info);
    }
}
