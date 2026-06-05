package com.amalitech.labresultsvalidator.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Schema(description = "Response returned after a new instructor account is created")
@Getter
@Builder
public class ProvisionInstructorResponse {

    @Schema(description = "Unique identifier of the newly created account")
    private final UUID id;

    @Schema(description = "Email address of the new instructor", example = "john.doe@amalitech.com")
    private final String email;
}
