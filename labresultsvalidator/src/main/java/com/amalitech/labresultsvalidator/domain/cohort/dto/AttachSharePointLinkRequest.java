package com.amalitech.labresultsvalidator.domain.cohort.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttachSharePointLinkRequest {

    @NotBlank(message = "folderUrl is required")
    @Pattern(
        regexp = "^https://[^/]+\\.sharepoint\\.com/.+",
        message = "folderUrl must be a valid SharePoint URL (https://<tenant>.sharepoint.com/...)"
    )
    private String folderUrl;
}
