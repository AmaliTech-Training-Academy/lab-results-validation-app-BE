package com.amalitech.labresultsvalidator.domain.auth.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.service.EmailService;
import com.amalitech.labresultsvalidator.domain.auth.dto.ForgotPasswordRequest;
import com.amalitech.labresultsvalidator.domain.auth.dto.ResetPasswordRequest;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.amalitech.labresultsvalidator.security.PasswordResetTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServicePasswordResetTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenService passwordResetTokenService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "frontendUrl", "http://localhost:3000");
    }

    // ── forgotPassword ────────────────────────────────────────────────────────

    @Test
    void forgotPassword_whenEmailExists_createsTokenAndSendsEmail() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("instructor@amalitech.com")
                .build();

        ForgotPasswordRequest request = new ForgotPasswordRequest();
        ReflectionTestUtils.setField(request, "email", "instructor@amalitech.com");

        when(userRepository.findByEmail("instructor@amalitech.com")).thenReturn(Optional.of(user));
        when(passwordResetTokenService.createToken("instructor@amalitech.com"))
                .thenReturn("test-reset-token");

        authService.forgotPassword(request);

        verify(passwordResetTokenService).createToken("instructor@amalitech.com");
        verify(emailService).sendPasswordResetEmail(
                eq("instructor@amalitech.com"),
                contains("test-reset-token")
        );
    }

    @Test
    void forgotPassword_whenEmailNotFound_doesNothingAndDoesNotThrow() {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        ReflectionTestUtils.setField(request, "email", "unknown@amalitech.com");

        when(userRepository.findByEmail("unknown@amalitech.com")).thenReturn(Optional.empty());

        authService.forgotPassword(request);

        verify(passwordResetTokenService, never()).createToken(anyString());
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    void forgotPassword_resetLinkContainsFrontendUrlAndToken() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("instructor@amalitech.com")
                .build();

        ForgotPasswordRequest request = new ForgotPasswordRequest();
        ReflectionTestUtils.setField(request, "email", "instructor@amalitech.com");

        when(userRepository.findByEmail("instructor@amalitech.com")).thenReturn(Optional.of(user));
        when(passwordResetTokenService.createToken(anyString())).thenReturn("abc-123-token");

        authService.forgotPassword(request);

        verify(emailService).sendPasswordResetEmail(
                anyString(),
                contains("http://localhost:3000/set-password?token=abc-123-token")
        );
    }

    // ── resetPassword ─────────────────────────────────────────────────────────

    @Test
    void resetPassword_withValidToken_updatesPasswordAndDeletesToken() {
        String token = UUID.randomUUID().toString();
        String newPassword = "NewSecure@2026";

        User user = User.builder()
                .id(UUID.randomUUID())
                .email("instructor@amalitech.com")
                .mustChangePassword(true)
                .build();

        ResetPasswordRequest request = new ResetPasswordRequest();
        ReflectionTestUtils.setField(request, "token", token);
        ReflectionTestUtils.setField(request, "newPassword", newPassword);

        when(passwordResetTokenService.getEmailForToken(token))
                .thenReturn("instructor@amalitech.com");
        when(userRepository.findByEmail("instructor@amalitech.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(newPassword)).thenReturn("$2a$hashed");

        authService.resetPassword(request);

        assertThat(user.getPasswordHash()).isEqualTo("$2a$hashed");
        assertThat(user.isMustChangePassword()).isFalse();
        verify(userRepository).save(user);
        verify(passwordResetTokenService).deleteToken(token);
    }

    @Test
    void resetPassword_withExpiredOrInvalidToken_throwsIllegalArgument() {
        String token = "expired-or-invalid-token";

        ResetPasswordRequest request = new ResetPasswordRequest();
        ReflectionTestUtils.setField(request, "token", token);
        ReflectionTestUtils.setField(request, "newPassword", "NewSecure@2026");

        when(passwordResetTokenService.getEmailForToken(token)).thenReturn(null);

        assertThatThrownBy(() -> authService.resetPassword(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid or expired reset token");

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any());
        verify(passwordResetTokenService, never()).deleteToken(anyString());
    }

    @Test
    void resetPassword_setsMusChangePasswordFalse() {
        String token = "valid-token";

        User user = User.builder()
                .id(UUID.randomUUID())
                .email("instructor@amalitech.com")
                .mustChangePassword(true)
                .build();

        ResetPasswordRequest request = new ResetPasswordRequest();
        ReflectionTestUtils.setField(request, "token", token);
        ReflectionTestUtils.setField(request, "newPassword", "NewPass@123");

        when(passwordResetTokenService.getEmailForToken(token))
                .thenReturn("instructor@amalitech.com");
        when(userRepository.findByEmail("instructor@amalitech.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        authService.resetPassword(request);

        assertThat(user.isMustChangePassword()).isFalse();
    }

    @Test
    void resetPassword_whenUserDeletedAfterTokenIssued_throwsResourceNotFound() {
        String token = "orphaned-token";

        ResetPasswordRequest request = new ResetPasswordRequest();
        ReflectionTestUtils.setField(request, "token", token);
        ReflectionTestUtils.setField(request, "newPassword", "NewPass@2026");

        when(passwordResetTokenService.getEmailForToken(token))
                .thenReturn("deleted@amalitech.com");
        when(userRepository.findByEmail("deleted@amalitech.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(request))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
