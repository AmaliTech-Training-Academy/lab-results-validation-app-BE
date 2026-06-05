package com.amalitech.labresultsvalidator.domain.user_module_assignment.dto;

import lombok.Builder;
import lombok.Getter;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class AssignModuleResponse {
    private UUID instructorId;
    private String instructorEmail;
    private List<AssignedModuleResponse> assignedModules;
}
