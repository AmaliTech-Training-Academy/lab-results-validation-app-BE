package com.amalitech.labresultsvalidator.domain.module.dto;

import com.amalitech.labresultsvalidator.domain.enums.ModuleStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PatchModuleRequest {

    @NotNull(message = "Status is required")
    private ModuleStatus status;
}
