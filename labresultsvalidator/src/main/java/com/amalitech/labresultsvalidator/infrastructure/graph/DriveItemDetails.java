package com.amalitech.labresultsvalidator.infrastructure.graph;

/**
 * Metadata for a single drive item, resolved by ID. {@code parentFolderName} is the immediate
 * parent folder's name (last segment of the item's parent path), or {@code null} if Graph
 * didn't return a path.
 */
public record DriveItemDetails(String name, String parentFolderName) {}