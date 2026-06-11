package com.amalitech.labresultsvalidator.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Request body for completing a password reset")
@Getter
@NoArgsConstructor
public class ResetPasswordRequest {

    @Schema(description = "One-time token received in the password reset email")
    @NotBlank(message = "Reset token is required")
    private String token;

    @Schema(description = "New password — must be at least 8 characters", example = "NewSecure@2026")
    @NotBlank(message = "New password is required")
    @Size(min = 8, message = "New password must be at least 8 characters")
    private String newPassword;
}
