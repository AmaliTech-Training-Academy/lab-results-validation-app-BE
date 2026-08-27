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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Read-only access to SharePoint drives via Microsoft Graph.
 *
 * <p>Every call routes through {@link GraphRetryExecutor} so throttling (429), transient
 * 5xx faults and mid-run token expiry are retried with backoff before the caller sees a
 * failure (B4 AC4). The executor must wrap the SDK call directly: it needs the
 * {@code ApiException} status and {@code Retry-After} header, both of which are lost once
 * the failure is wrapped in a {@link GraphAccessException}.
 */
@Service
public class GraphDriveService {

    private static final Logger LOG = LoggerFactory.getLogger(GraphDriveService.class);

    /**
     * Fields needed from a single-item GET. {@code file} carries the {@code hashes} facet
     * (and therefore {@code quickXorHash}); {@code cTag} is the content version marker.
     * Requesting them explicitly keeps the response small and makes the dependency obvious.
     */
    private static final String[] ITEM_SELECT = {
        "id", "name", "size", "cTag", "eTag", "file", "webUrl", "parentReference"
    };

    private final GraphServiceClient graphServiceClient;
    private final AzureGraphProperties azureGraphProperties;
    private final SharePointProperties sharePointProperties;
    private final GraphRetryExecutor retry;

    public GraphDriveService(
        GraphServiceClient graphServiceClient,
        AzureGraphProperties azureGraphProperties,
        SharePointProperties sharePointProperties,
        GraphRetryExecutor retry
    ) {
        this.graphServiceClient = graphServiceClient;
        this.azureGraphProperties = azureGraphProperties;
        this.sharePointProperties = sharePointProperties;
        this.retry = retry;
    }

    public DriveItemInfo resolveFolder(String sharepointUrl)
            throws GraphAccessException, GraphItemTypeException, GraphSiteViolationException {
        String encodedToken = "u!" + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(sharepointUrl.getBytes(StandardCharsets.UTF_8));

        String notAccessible = "Cannot access the SharePoint folder at " + sharepointUrl
            + ". Check the path and that Validata has been granted access.";

        DriveItem item;
        try {
            item = retry.execute("resolve shared link", () -> graphServiceClient.shares()
                .bySharedDriveItemId(encodedToken)
                .driveItem()
                .get());
        } catch (GraphAccessException ex) {
            LOG.warn("Graph API call failed resolving SharePoint URL: {}", ex.getMessage());
            throw new GraphAccessException(notAccessible, ex);
        }

        if (item == null) {
            throw new GraphAccessException(notAccessible);
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
        if (configured == null || resolved == null) {
            return false;
        }
        // Graph may return just the GUID or the full "hostname,guid1,guid2" composite.
        return configured.equals(resolved) || configured.contains(resolved);
    }

    /**
     * Graph pages folder listings (~200 items per page by default) — a scores or scenario
     * folder with more children than that would silently lose the overflow if only the first
     * page were read. Each page is fetched (and retried) separately via {@code withUrl} on
     * {@code @odata.nextLink}, so a fault partway through pagination only re-fetches the page
     * it failed on, not the whole listing.
     */
    public List<DriveItemInfo> listChildren(String driveId, String itemId) throws GraphAccessException {
        List<DriveItemInfo> result = new ArrayList<>();
        String nextLink = null;

        do {
            String urlForThisPage = nextLink;
            DriveItemCollectionResponse response;
            try {
                response = retry.execute("list children of " + itemId, () -> {
                    var request = graphServiceClient.drives()
                        .byDriveId(driveId)
                        .items()
                        .byDriveItemId(itemId)
                        .children();
                    return urlForThisPage == null ? request.get() : request.withUrl(urlForThisPage).get();
                });
            } catch (GraphAccessException ex) {
                LOG.warn("Graph API call failed listing children for item {}: {}", itemId, ex.getMessage());
                throw new GraphAccessException(
                    "Cannot list contents of the SharePoint folder (driveId=" + driveId
                        + ", itemId=" + itemId + ").",
                    ex
                );
            }

            if (response != null && response.getValue() != null) {
                for (DriveItem child : response.getValue()) {
                    result.add(toDriveItemInfo(child, driveId));
                }
            }
            nextLink = response == null ? null : response.getOdataNextLink();
        } while (nextLink != null);

        return result;
    }

    private DriveItemInfo toDriveItemInfo(DriveItem child, String driveId) {
        boolean isFolder = child.getFolder() != null;
        String childSiteId = null;
        String childDriveId = driveId;
        if (child.getParentReference() != null) {
            childSiteId = child.getParentReference().getSiteId();
            if (child.getParentReference().getDriveId() != null) {
                childDriveId = child.getParentReference().getDriveId();
            }
        }
        return new DriveItemInfo(childDriveId, child.getId(), child.getName(), isFolder, childSiteId);
    }

    /**
     * Single-item metadata GET (B3 AC1): reads the item's {@code quickXorHash}, content version
     * (cTag) and size, plus the name and immediate parent folder name used to build its S3 key.
     *
     * <p>Size arrives here so callers can reject an oversized workbook <em>before</em> spending
     * the download.
     */
    public DriveItemDetails getItem(String driveId, String itemId) throws GraphAccessException {
        DriveItem item;
        try {
            item = retry.execute("get item " + itemId, () -> graphServiceClient.drives()
                .byDriveId(driveId)
                .items()
                .byDriveItemId(itemId)
                .get(config -> config.queryParameters.select = ITEM_SELECT));
        } catch (GraphAccessException ex) {
            LOG.warn("Graph API call failed fetching item {}: {}", itemId, ex.getMessage());
            throw new GraphAccessException(
                "Cannot fetch SharePoint item metadata (driveId=" + driveId + ", itemId=" + itemId + ").",
                ex
            );
        }

        if (item == null) {
            throw new GraphAccessException("SharePoint returned no metadata for item " + itemId + ".");
        }

        String parentFolderName = null;
        if (item.getParentReference() != null && item.getParentReference().getPath() != null) {
            String path = item.getParentReference().getPath();
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                parentFolderName = path.substring(lastSlash + 1);
            }
        }

        String quickXorHash = null;
        if (item.getFile() != null && item.getFile().getHashes() != null) {
            quickXorHash = item.getFile().getHashes().getQuickXorHash();
        }

        return new DriveItemDetails(
            item.getName(),
            parentFolderName,
            quickXorHash,
            item.getCTag(),
            item.getSize(),
            item.getWebUrl()
        );
    }

    /**
     * Downloads an item's content.
     *
     * <p>The read is bounded at the configured workbook cap rather than checked afterwards: an
     * unbounded {@code readAllBytes} would exhaust the heap on an oversized file before any
     * size check could fire. Streaming and reading happen inside one retryable unit so a fault
     * partway through the body is retried rather than surfacing as a truncated workbook.
     */
    public byte[] downloadFile(String driveId, String itemId) throws GraphAccessException {
        long cap = sharePointProperties.maxWorkbookBytes();
        int readLimit = (int) Math.min(cap + 1, Integer.MAX_VALUE);

        byte[] bytes = retry.execute("download item " + itemId, () -> {
            try (InputStream stream = graphServiceClient.drives()
                    .byDriveId(driveId)
                    .items()
                    .byDriveItemId(itemId)
                    .content()
                    .get()) {

                if (stream == null) {
                    return null;
                }
                return stream.readNBytes(readLimit);
            } catch (IOException ex) {
                // Surfaced as an IOException cause so the retry executor treats it as transient.
                throw new UncheckedIOException("Failed reading content for item " + itemId, ex);
            }
        });

        if (bytes == null) {
            throw new GraphAccessException("SharePoint returned empty content for item " + itemId + ".");
        }

        if (bytes.length > cap) {
            throw new GraphAccessException(
                "File exceeds the maximum allowed size of " + cap + " bytes.");
        }

        return bytes;
    }
}
