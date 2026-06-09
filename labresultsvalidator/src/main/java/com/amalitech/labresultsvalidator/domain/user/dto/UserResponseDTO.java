package com.amalitech.labresultsvalidator.domain.user.dto;

import com.amalitech.labresultsvalidator.domain.user_module_assignment.dto.AssignedModuleResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Schema(description = "Instructor summary returned by the list-instructors endpoint")
@Getter
@Builder
public class UserResponseDTO {

    @Schema(description = "Instructor email address", example = "instructor@amalitech.com")
    private String email;

    @Schema(description = "Whether the account is active", example = "true")
    private boolean active;

    @Schema(description = "Modules currently assigned to this instructor")
    private List<AssignedModuleResponse> assignedModules;
}
