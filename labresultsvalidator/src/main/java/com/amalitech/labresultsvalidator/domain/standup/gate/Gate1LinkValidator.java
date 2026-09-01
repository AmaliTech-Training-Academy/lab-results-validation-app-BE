package com.amalitech.labresultsvalidator.domain.standup.gate;

import com.amalitech.labresultsvalidator.infrastructure.graph.AzureGraphProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphItemTypeException;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphSiteViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class Gate1LinkValidator {

    private static final Logger LOG = LoggerFactory.getLogger(Gate1LinkValidator.class);

    private static final Pattern SHAREPOINT_URL =
        Pattern.compile("^https://[^/]+\\.sharepoint\\.com/.+", Pattern.CASE_INSENSITIVE);

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

        if (!SHAREPOINT_URL.matcher(sharepointUrl).matches()) {
            return new Gate1Result(
                GateResult.fail("link", sharepointUrl, "G1-INVALID-URL",
                    "The URL is not a valid SharePoint link. Expected format: https://<tenant>.sharepoint.com/..."),
                null
            );
        }

        DriveItemInfo info;
        try {
            info = graphDriveService.resolveFolder(sharepointUrl);
        } catch (GraphAccessException ex) {
            // Full exception (cause chain intact — see GraphRetryExecutor/GraphDriveService) at ERROR:
            // this is the only place in the stack that logs Gate 1's failure with the SharePoint URL
            // that triggered it, which is exactly what's needed to tell "genuinely no access" apart
            // from a transient Graph fault or an internal bug from the log alone, without reproducing.
            LOG.error("[gate1] could not resolve SharePoint URL '{}': {}", sharepointUrl, ex.getMessage(), ex);
            return new Gate1Result(
                GateResult.fail("link", null, "G1-ACCESS",
                    "Cannot access the SharePoint folder at " + sharepointUrl
                        + ". Check the path and that Validata has been granted access."),
                null
            );
        } catch (GraphItemTypeException ex) {
            LOG.warn("[gate1] link resolves to a file, not a folder: '{}'", sharepointUrl);
            return new Gate1Result(
                GateResult.fail("link", null, "G1-NOT-FOLDER",
                    "The link resolves to a file, not a folder. Provide a link to a SharePoint folder."),
                null
            );
        } catch (GraphSiteViolationException ex) {
            LOG.warn("[gate1] link '{}' resolved outside the sanctioned SharePoint site: {}",
                sharepointUrl, ex.getMessage());
            return new Gate1Result(
                GateResult.fail("link", null, "G1-SITE-VIOLATION",
                    "The link points to a location outside the sanctioned SharePoint site."),
                null
            );
        }

        return new Gate1Result(GateResult.pass(), info);
    }
}
