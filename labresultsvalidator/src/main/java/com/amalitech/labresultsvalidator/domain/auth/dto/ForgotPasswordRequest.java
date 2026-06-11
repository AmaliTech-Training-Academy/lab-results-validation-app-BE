package com.amalitech.labresultsvalidator.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Request body for initiating a password reset")
@Getter
@NoArgsConstructor
public class ForgotPasswordRequest {

    @Schema(description = "Email address associated with the account", example = "instructor@amalitech.com")
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    private String email;
}
