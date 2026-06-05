package com.amalitech.labresultsvalidator.domain.user_module_assignment.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Getter
@NoArgsConstructor
public class AssignModuleRequest {
    @NotEmpty(message = "At least one module ID must be provided")
    private List<UUID> moduleIds;
}
