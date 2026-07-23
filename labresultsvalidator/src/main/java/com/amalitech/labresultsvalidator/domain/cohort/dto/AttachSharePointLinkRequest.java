package com.amalitech.labresultsvalidator.domain.cohort.dto;

import jakarta.validation.constraints.NotBlank;
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
    private String folderUrl;
}
