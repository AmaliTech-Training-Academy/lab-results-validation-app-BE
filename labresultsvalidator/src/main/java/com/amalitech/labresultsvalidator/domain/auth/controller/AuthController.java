package com.amalitech.labresultsvalidator.domain.auth.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.common.utils.CookieUtils;
import com.amalitech.labresultsvalidator.domain.auth.dto.ChangePasswordRequest;
import com.amalitech.labresultsvalidator.domain.auth.dto.ForgotPasswordRequest;
import com.amalitech.labresultsvalidator.domain.auth.dto.LoginRequest;
import com.amalitech.labresultsvalidator.domain.auth.dto.LoginResponse;
import com.amalitech.labresultsvalidator.domain.auth.dto.ResetPasswordRequest;
import com.amalitech.labresultsvalidator.domain.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "Login and token management")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class  AuthController {

    private final AuthService authService;
    private final CookieUtils cookieUtils;

    @Operation(
        summary = "Login",
        description = "Authenticate with email and password. Returns a JWT token for use in the Authorize dialog."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Login successful — JWT token returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Bad credentials or account disabled",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {

        LoginResponse loginResponse = authService.login(request);
        cookieUtils.setRefreshTokenCookie(
                response,
                loginResponse.getRefreshToken()
        );

        return ResponseEntity.ok(
                ApiResponse.success("Login successful", loginResponse)
        );
    }

    @Operation(
        summary = "Refresh JWT token",
        description = "Use the refresh token cookie to get a new JWT access token."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Token refreshed successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Missing or invalid refresh token",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @SecurityRequirements
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            HttpServletRequest request,
            HttpServletResponse response) {

        LoginResponse loginResponse = authService.refresh(request);
        cookieUtils.setRefreshTokenCookie(
                response,
                loginResponse.getRefreshToken()
        );

        return ResponseEntity.ok(
                ApiResponse.success("Token refreshed successfully", loginResponse)
        );
    }

    @Operation(
        summary = "Logout",
        description = "Invalidate the current session and clear the refresh token cookie."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Logout successful"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Unauthorized",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @SecurityRequirements
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        authService.logout(request);
        cookieUtils.clearRefreshTokenCookie(response);

        return ResponseEntity.ok(
                ApiResponse.success("Logged out successfully", null)
        );
    }

    @Operation(
        summary = "Forgot password",
        description = "Sends a password reset link to the provided email address. "
            + "Always returns 200 regardless of whether the email exists, "
            + "to prevent account enumeration. The reset link expires in 15 minutes."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Reset email sent if the address is registered"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Validation error — email missing or malformed",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @SecurityRequirements
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(
                ApiResponse.success(
                        "If an account with that email exists, a password reset link has been sent."
                                + " Please check your inbox and spam folder.",
                        null)
        );
    }

    @Operation(
        summary = "Reset password",
        description = "Sets a new password using the one-time token from the reset email. "
            + "The token expires after 15 minutes and is invalidated after a single use."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Password reset successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error, token missing, or token invalid / expired",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @SecurityRequirements
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(
                ApiResponse.success("Password reset successfully. Please log in with your new password.", null)
        );
    }

    @Operation(
        summary = "Change password",
        description = "Required on first login when mustChangePassword is true. "
            + "Issues a new token with mustChangePassword = false on success."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Password changed — new JWT returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error, current password incorrect, or new password same as current")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<LoginResponse>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication,
            HttpServletResponse response) {

        LoginResponse loginResponse = authService.changePassword(
                authentication.getName(), request
        );
        cookieUtils.setRefreshTokenCookie(response, loginResponse.getRefreshToken());

        return ResponseEntity.ok(
                ApiResponse.success("Password changed successfully", loginResponse)
        );
    }
}
