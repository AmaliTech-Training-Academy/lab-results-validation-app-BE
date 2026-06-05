package com.amalitech.labresultsvalidator.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Request body for provisioning a new instructor account")
@Getter
@NoArgsConstructor
public class ProvisionInstructorRequest {

    @Schema(
        description = "Instructor's work email — must be an @amalitech.com address",
        example = "john.doe@amalitech.com"
    )
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    @Pattern(
        regexp = "^[\\w.+\\-]+@amalitech\\.com$",
        message = "Email must be an @amalitech.com address"
    )
    private String email;
}
