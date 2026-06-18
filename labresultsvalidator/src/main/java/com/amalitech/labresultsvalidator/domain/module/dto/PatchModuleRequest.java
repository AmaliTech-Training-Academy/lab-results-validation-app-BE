package com.amalitech.labresultsvalidator.domain.module.dto;

import com.amalitech.labresultsvalidator.domain.enums.ModuleStatus;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PatchModuleRequest {

    @Size(max = 200, message = "Module name must not exceed 200 characters")
    private String name;

    private ModuleStatus status;
}
