package com.amalitech.labresultsvalidator.domain.user_module_assignment.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class AssignedModuleResponse {
    private UUID moduleId;
    private String moduleName;
    private String specializationName;
}
