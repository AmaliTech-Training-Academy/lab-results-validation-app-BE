package com.amalitech.labresultsvalidator.infrastructure.graph;

import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphItemTypeException;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphSiteViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Serves the {@link GraphDriveService} contract from a directory on disk instead of SharePoint.
 *
 * <p>Selected by {@code validata.sharepoint.source=fixtures}. Nothing else in the application
 * changes: the gates, the sync job and the ingestion pipeline are unaware, because the boundary
 * they depend on is unchanged. That is the point — the code under test is the real code.
 *
 * <h2>How a directory becomes a drive</h2>
 * The configured {@code root} stands in for the drive. An item's id <em>is</em> its path relative
 * to that root, which makes failures legible: an id reads {@code Demo Happy Path Cohort/Lab
 * Scores/Module 1 Grading.xlsx} rather than an opaque token. The root itself has the empty id.
 * Every resolved path is checked to still fall inside the root, so a crafted id cannot walk out.
 *
 * <h2>Change detection</h2>
 * The contract's one demanding requirement is that {@code quickXorHash} and {@code versionId}
 * change when — and only when — content changes. Both are derived from a SHA-256 of the file's
 * bytes, so they are content-addressed by construction: edit a fixture and the next run sees a
 * changed file; copy it elsewhere untouched and the run correctly sees no change. This is
 * deliberately stronger than SharePoint, which publishes a new version on a lag and is the single
 * most common source of a sync that looks broken but is not (ENV-2). A fixture run has no such
 * window, which is exactly why an ingestion test can be deterministic here and cannot be there.
 *
 * <p>The hash is not Microsoft's QuickXorHash algorithm and does not need to be. Validata only
 * ever compares the value it stored against the value it was handed; it never recomputes or
 * validates one. The name is kept because the column it lands in is named for it.
 */
@Service
@ConditionalOnProperty(
    prefix = "validata.sharepoint", name = "source", havingValue = "fixtures")
public class FixtureDriveService implements GraphDriveService {

    private static final Logger LOG = LoggerFactory.getLogger(FixtureDriveService.class);

    /** One synthetic drive; the id is constant because a fixture root is a single drive. */
    private static final String DRIVE_ID = "fixture-drive";

    private final FixtureDriveProperties fixtureProperties;
    private final AzureGraphProperties azureGraphProperties;
    private final SharePointProperties sharePointProperties;
    private final Path root;

    public FixtureDriveService(
        FixtureDriveProperties fixtureProperties,
        AzureGraphProperties azureGraphProperties,
        SharePointProperties sharePointProperties
    ) {
        this.fixtureProperties = fixtureProperties;
        this.azureGraphProperties = azureGraphProperties;
        this.sharePointProperties = sharePointProperties;
        this.root = Paths.get(fixtureProperties.root()).toAbsolutePath().normalize();

        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                "validata.sharepoint.fixture.root is not a directory: " + root
                    + ". Fixture mode cannot start without it.");
        }
        LOG.warn("[fixture-drive] SharePoint is being served from {} — this is NOT the real drive. "
            + "Set validata.sharepoint.source=graph for production behaviour.", root);
    }

    /**
     * Resolves a folder the way the real implementation does, including both refusal paths, so a
     * test can drive Gate 1 to failure and not only to success.
     *
     * <p>The "url" is treated as a path relative to the root; a full URL is accepted too and
     * everything up to and including {@code webUrlBase} is stripped first, so a cohort can be
     * configured with either form.
     */
    @Override
    public DriveItemInfo resolveFolder(String sharepointUrl)
            throws GraphAccessException, GraphItemTypeException, GraphSiteViolationException {

        String relative = toRelativePath(sharepointUrl);
        String notAccessible = "Cannot access the SharePoint folder at " + sharepointUrl
            + ". Check the path and that Validata has been granted access.";

        Path target = resolveInsideRoot(relative);
        if (target == null || !Files.exists(target)) {
            throw new GraphAccessException(notAccessible);
        }
        if (!Files.isDirectory(target)) {
            throw new GraphItemTypeException(
                "The link resolves to a file, not a folder. Provide a link to a SharePoint folder."
            );
        }

        String siteId = fixtureProperties.siteIdOrDefault();
        if (!sanctionedSiteMatches(azureGraphProperties.sanctionedSiteId(), siteId)) {
            LOG.warn("[fixture-drive] site id mismatch — resolved='{}' configured='{}'",
                siteId, azureGraphProperties.sanctionedSiteId());
            throw new GraphSiteViolationException(
                "The link points to a location outside the sanctioned SharePoint site."
            );
        }

        return new DriveItemInfo(DRIVE_ID, relativeId(target), fileName(target), true, siteId);
    }

    /** Mirrors the real implementation's tolerance for a GUID-only or composite site id. */
    private boolean sanctionedSiteMatches(String configured, String resolved) {
        if (configured == null || resolved == null) {
            return false;
        }
        return configured.equals(resolved) || configured.contains(resolved);
    }

    @Override
    public List<DriveItemInfo> listChildren(String driveId, String itemId)
            throws GraphAccessException {
        Path dir = requireExisting(itemId);
        if (!Files.isDirectory(dir)) {
            throw new GraphAccessException("Item " + itemId + " is not a folder.");
        }

        List<DriveItemInfo> children = new ArrayList<>();
        // Sorted so a listing is reproducible run to run; Graph guarantees no order, but a test
        // that fails differently on each run is worse than one that is deterministically wrong.
        try (Stream<Path> entries = Files.list(dir)) {
            entries.sorted(Comparator.comparing(FixtureDriveService::fileName))
                .filter(FixtureDriveService::isVisible)
                .forEach(child -> children.add(new DriveItemInfo(
                    DRIVE_ID,
                    relativeId(child),
                    fileName(child),
                    Files.isDirectory(child),
                    fixtureProperties.siteIdOrDefault())));
        } catch (IOException ex) {
            throw new GraphAccessException("Cannot list contents of " + itemId + ".", ex);
        }
        return children;
    }

    @Override
    public DriveItemDetails getItem(String driveId, String itemId) throws GraphAccessException {
        Path item = requireExisting(itemId);
        Path parent = item.getParent();

        try {
            String contentHash = Files.isDirectory(item) ? null : sha256(Files.readAllBytes(item));
            return new DriveItemDetails(
                fileName(item),
                parent == null || parent.equals(root) ? null : fileName(parent),
                contentHash == null ? null : Base64.getEncoder()
                    .encodeToString(hexToBytes(contentHash)),
                contentHash == null ? null : "c:" + contentHash.substring(0, 16),
                Files.isDirectory(item) ? null : Files.size(item),
                webUrl(item));
        } catch (IOException ex) {
            throw new GraphAccessException("Cannot read metadata for " + itemId + ".", ex);
        }
    }

    @Override
    public byte[] downloadFile(String driveId, String itemId) throws GraphAccessException {
        Path file = requireExisting(itemId);
        if (Files.isDirectory(file)) {
            throw new GraphAccessException("Item " + itemId + " is a folder, not a file.");
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new GraphAccessException("Failed reading content for item " + itemId + ".", ex);
        }

        // Enforced here as well as in the real implementation: a cap that only production applies
        // is a cap no test can prove.
        long cap = sharePointProperties.maxWorkbookBytes();
        if (bytes.length > cap) {
            throw new GraphAccessException(
                "File exceeds the maximum allowed size of " + cap + " bytes.");
        }
        return bytes;
    }

    // ── path plumbing ─────────────────────────────────────────────────────────

    private Path requireExisting(String itemId) throws GraphAccessException {
        Path path = resolveInsideRoot(itemId);
        if (path == null || !Files.exists(path)) {
            throw new GraphAccessException("Item " + itemId + " does not exist in the fixture root.");
        }
        return path;
    }

    /** Returns null when the id would escape the root, which callers treat as "not accessible". */
    private Path resolveInsideRoot(String relative) {
        String cleaned = relative == null ? "" : relative.replace('\\', '/').trim();
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        Path candidate = (cleaned.isEmpty() ? root : root.resolve(cleaned)).normalize();
        return candidate.startsWith(root) ? candidate : null;
    }

    private String relativeId(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private String toRelativePath(String sharepointUrl) {
        if (sharepointUrl == null) {
            return "";
        }
        String base = fixtureProperties.webUrlBaseOrDefault();
        String value = sharepointUrl.startsWith(base)
            ? sharepointUrl.substring(base.length())
            : sharepointUrl;
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String webUrl(Path item) {
        String encoded = URLEncoder.encode(relativeId(item), StandardCharsets.UTF_8)
            .replace("%2F", "/")
            .replace("+", "%20");
        return fixtureProperties.webUrlBaseOrDefault() + "/" + encoded;
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    /** Skips dotfiles and the LibreOffice lock files an open spreadsheet leaves behind. */
    private static boolean isVisible(Path path) {
        String name = fileName(path);
        return !name.startsWith(".") && !name.startsWith("~$");
    }

    private static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required and was not available", ex);
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
