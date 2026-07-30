package com.amalitech.labresultsvalidator.infrastructure.graph;

/**
 * Metadata for a single drive item, resolved by ID via a single-item GET (B3 AC1).
 *
 * @param name             the item's filename
 * @param parentFolderName the immediate parent folder's name (last segment of the item's
 *                         parent path), or {@code null} if Graph didn't return a path
 * @param quickXorHash     SharePoint's server-computed content hash, recorded for audit
 *                         provenance. {@code null} when Graph does not supply one
 * @param versionId        the content tag (cTag), which changes when the file's content
 *                         changes - recorded as {@code sharepoint_version_id}
 * @param sizeBytes        content length, checked against the workbook cap *before* download
 * @param webUrl           source link, for audit provenance
 */
public record DriveItemDetails(
    String name,
    String parentFolderName,
    String quickXorHash,
    String versionId,
    Long sizeBytes,
    String webUrl
) {
    /**
     * @return true when Graph supplied a content hash for this item
     */
    public boolean hasQuickXorHash() {
        return quickXorHash != null && !quickXorHash.isBlank();
    }
}
