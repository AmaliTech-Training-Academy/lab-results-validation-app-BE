package com.amalitech.labresultsvalidator.infrastructure.graph;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphItemTypeException;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphSiteViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class GraphDriveService {

    private static final Logger LOG = LoggerFactory.getLogger(GraphDriveService.class);

    private final GraphServiceClient graphServiceClient;
    private final AzureGraphProperties azureGraphProperties;
    private final SharePointProperties sharePointProperties;

    public GraphDriveService(
        GraphServiceClient graphServiceClient,
        AzureGraphProperties azureGraphProperties,
        SharePointProperties sharePointProperties
    ) {
        this.graphServiceClient = graphServiceClient;
        this.azureGraphProperties = azureGraphProperties;
        this.sharePointProperties = sharePointProperties;
    }

    public DriveItemInfo resolveFolder(String sharepointUrl)
            throws GraphAccessException, GraphItemTypeException, GraphSiteViolationException {
        String encodedToken = "u!" + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(sharepointUrl.getBytes(StandardCharsets.UTF_8));

        DriveItem item;
        try {
            item = graphServiceClient.shares()
                .bySharedDriveItemId(encodedToken)
                .driveItem()
                .get();
        } catch (Exception ex) {
            LOG.warn("Graph API call failed resolving SharePoint URL: {}", ex.getMessage());
            throw new GraphAccessException(
                "Cannot access the SharePoint folder at " + sharepointUrl
                    + ". Check the path and that LabGate has been granted access.",
                ex
            );
        }

        if (item == null) {
            throw new GraphAccessException(
                "Cannot access the SharePoint folder at " + sharepointUrl
                    + ". Check the path and that LabGate has been granted access."
            );
        }

        if (item.getFolder() == null) {
            throw new GraphItemTypeException(
                "The link resolves to a file, not a folder. Provide a link to a SharePoint folder."
            );
        }

        String siteId = null;
        if (item.getParentReference() != null) {
            siteId = item.getParentReference().getSiteId();
        }

        if (!sanctionedSiteMatches(azureGraphProperties.sanctionedSiteId(), siteId)) {
            LOG.warn("Site ID mismatch — resolved='{}' configured='{}'",
                siteId, azureGraphProperties.sanctionedSiteId());
            throw new GraphSiteViolationException(
                "The link points to a location outside the sanctioned SharePoint site."
            );
        }

        String driveId = null;
        if (item.getParentReference() != null) {
            driveId = item.getParentReference().getDriveId();
        }

        return new DriveItemInfo(driveId, item.getId(), item.getName(), true, siteId);
    }

    private boolean sanctionedSiteMatches(String configured, String resolved) {
        if (configured == null || resolved == null) return false;
        // Graph may return just the GUID or the full "hostname,guid1,guid2" composite.
        return configured.equals(resolved) || configured.contains(resolved);
    }

    public List<DriveItemInfo> listChildren(String driveId, String itemId) throws GraphAccessException {
        DriveItemCollectionResponse response;
        try {
            response = graphServiceClient.drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId(itemId)
                .children()
                .get();
        } catch (Exception ex) {
            LOG.warn("Graph API call failed listing children for item {}: {}", itemId, ex.getMessage());
            throw new GraphAccessException(
                "Cannot list contents of the SharePoint folder (driveId=" + driveId
                    + ", itemId=" + itemId + ").",
                ex
            );
        }

        List<DriveItemInfo> result = new ArrayList<>();
        if (response == null || response.getValue() == null) {
            return result;
        }

        for (DriveItem child : response.getValue()) {
            boolean isFolder = child.getFolder() != null;
            String childSiteId = null;
            String childDriveId = driveId;
            if (child.getParentReference() != null) {
                childSiteId = child.getParentReference().getSiteId();
                if (child.getParentReference().getDriveId() != null) {
                    childDriveId = child.getParentReference().getDriveId();
                }
            }
            result.add(new DriveItemInfo(childDriveId, child.getId(), child.getName(), isFolder, childSiteId));
        }
        return result;
    }

    public byte[] downloadFile(String driveId, String itemId) throws GraphAccessException {
        InputStream stream;
        try {
            stream = graphServiceClient.drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId(itemId)
                .content()
                .get();
        } catch (Exception ex) {
            LOG.warn("Graph API call failed downloading item {}: {}", itemId, ex.getMessage());
            throw new GraphAccessException(
                "Cannot download file from SharePoint (driveId=" + driveId
                    + ", itemId=" + itemId + ").",
                ex
            );
        }

        if (stream == null) {
            throw new GraphAccessException(
                "SharePoint returned empty content for item " + itemId + "."
            );
        }

        byte[] bytes;
        try {
            bytes = stream.readAllBytes();
        } catch (IOException ex) {
            LOG.warn("Failed to read stream for item {}: {}", itemId, ex.getMessage());
            throw new GraphAccessException("Failed to read file content from SharePoint.", ex);
        }

        if (bytes.length > sharePointProperties.maxWorkbookBytes()) {
            throw new GraphAccessException("File exceeds the maximum allowed size.");
        }

        return bytes;
    }
}
