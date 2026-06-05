package com.amalitech.labresultsvalidator.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Request body for changing the authenticated user's password")
@Getter
@NoArgsConstructor
public class ChangePasswordRequest {

    @Schema(
        description = "The user's current (temporary) password",
        example = "Temp@Pass1"
    )
    @NotBlank(message = "Current password is required")
    private String currentPassword;

    @Schema(
        description = "The new password — must be at least 8 characters and differ from the current password",
        example = "NewSecure@2026"
    )
    @NotBlank(message = "New password is required")
    @Size(min = 8, message = "New password must be at least 8 characters")
    private String newPassword;
}
