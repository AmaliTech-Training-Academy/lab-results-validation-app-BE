package com.amalitech.labresultsvalidator.infrastructure.graph;

public record DriveItemInfo(
    String driveId,
    String itemId,
    String name,
    boolean isFolder,
    String siteId
) {}
