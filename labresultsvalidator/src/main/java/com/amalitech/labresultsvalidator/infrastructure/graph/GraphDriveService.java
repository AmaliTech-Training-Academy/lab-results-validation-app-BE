package com.amalitech.labresultsvalidator.infrastructure.graph;

import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphItemTypeException;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphSiteViolationException;

import java.util.List;

/**
 * Read-only access to the drive that holds a cohort's folder — the single boundary between
 * Validata and SharePoint.
 *
 * <p>Every Graph-shaped call in the application funnels through these four methods, which is
 * what makes the boundary swappable. Nothing here mentions the Graph SDK: the parameters are
 * strings, the return types are local records ({@link DriveItemInfo}, {@link DriveItemDetails})
 * and the failures are our own exceptions. An implementation is free to serve the same contract
 * from anywhere.
 *
 * <p>Two implementations exist, selected by {@code validata.sharepoint.source}:
 * <ul>
 *   <li>{@code graph} (default) — {@link MicrosoftGraphDriveService}, the real thing.</li>
 *   <li>{@code fixtures} — {@link FixtureDriveService}, serving a directory tree from disk so
 *       ingestion can be exercised deterministically, with no tenant, credentials or network.</li>
 * </ul>
 *
 * <p><strong>Why this interface exists.</strong> Every meaningful flow in the product — the
 * stand-up gates, the weekly sync, ingestion — begins by reading files from SharePoint. While
 * that was reachable only through a live tenant, none of it could be tested deterministically:
 * the tests that exist stop at the unit boundary, and the one thing SharePoint reliably does is
 * publish an edit as a new version *late*, so a run triggered promptly honestly reports "no
 * changes" (ENV-2). Faking this boundary is what makes the layer below testable at all.
 */
public interface GraphDriveService {

    /**
     * Resolves the admin-supplied cohort folder link to a drive item.
     *
     * @throws GraphItemTypeException     the link resolves to a file rather than a folder
     * @throws GraphSiteViolationException the target lies outside the sanctioned tenant site
     */
    DriveItemInfo resolveFolder(String sharepointUrl)
        throws GraphAccessException, GraphItemTypeException, GraphSiteViolationException;

    /** Lists the immediate children of a folder. Order is not guaranteed. */
    List<DriveItemInfo> listChildren(String driveId, String itemId) throws GraphAccessException;

    /**
     * Metadata for a single item, including the content hash and version marker that drive
     * change detection. Callers rely on {@code quickXorHash} and {@code versionId} changing
     * when — and only when — the file's content changes.
     */
    DriveItemDetails getItem(String driveId, String itemId) throws GraphAccessException;

    /** Downloads an item's bytes. Implementations enforce the configured size cap. */
    byte[] downloadFile(String driveId, String itemId) throws GraphAccessException;
}
