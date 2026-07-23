package com.amalitech.labresultsvalidator.domain.cohort.dto;

import jakarta.validation.constraints.NotBlank;

public record SetSharePointLinkRequest(
    @NotBlank(message = "SharePoint folder URL is required") String sharepointFolderUrl
) {}
