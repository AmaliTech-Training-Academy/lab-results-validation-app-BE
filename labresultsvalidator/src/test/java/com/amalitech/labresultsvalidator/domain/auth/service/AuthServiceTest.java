package com.amalitech.labresultsvalidator.domain.auth.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.utils.CookieUtils;
import com.amalitech.labresultsvalidator.domain.auth.dto.ChangePasswordRequest;
import com.amalitech.labresultsvalidator.domain.auth.dto.LoginRequest;
import com.amalitech.labresultsvalidator.domain.auth.dto.LoginResponse;
import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.amalitech.labresultsvalidator.security.JwtService;
import com.amalitech.labresultsvalidator.security.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CookieUtils cookieUtils;

    @InjectMocks
    private AuthService authService;

    private User buildUser(UUID id, boolean active, boolean mustChange) {
        return User.builder()
                .id(id)
                .email("user@test.com")
                .passwordHash("hashed")
                .role(UserRole.INSTRUCTOR)
                .isActive(active)
                .mustChangePassword(mustChange)
                .build();
    }

    // --- login ---

    @Test
    void login_authenticatesUser_andReturnsTokensWithCorrectFields() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, true, false);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(user);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(auth);
        when(jwtService.generateToken(user)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(user)).thenReturn("refresh-token");

        LoginRequest request = new LoginRequest();
        LoginResponse response = authService.login(request);

        assertThat(response.getToken()).isEqualTo("access-token");
        assertThat(response.getEmail()).isEqualTo("user@test.com");
        assertThat(response.getRole()).isEqualTo("INSTRUCTOR");
        assertThat(response.isMustChangePassword()).isFalse();
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void login_storesRefreshToken_usingUserId() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, true, false);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(user);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(jwtService.generateToken(user)).thenReturn("at");
        when(jwtService.generateRefreshToken(user)).thenReturn("rt");

        authService.login(new LoginRequest());

        verify(refreshTokenService).storeRefreshToken(userId.toString(), "rt");
    }

    // --- refresh ---

    @Test
    void refresh_whenNoCookie_throwsRuntimeException() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(cookieUtils.extractRefreshTokenFromCookie(request)).thenReturn(null);

        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Refresh token not found");
    }

    @Test
    void refresh_whenTokenExpired_throwsRuntimeException() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(cookieUtils.extractRefreshTokenFromCookie(request)).thenReturn("expired-token");
        when(jwtService.isTokenExpired("expired-token")).thenReturn(true);

        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Refresh token expired");
    }

    @Test
    void refresh_whenTokenNotInStore_throwsRuntimeException() {
        UUID userId = UUID.randomUUID();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(cookieUtils.extractRefreshTokenFromCookie(request)).thenReturn("token");
        when(jwtService.isTokenExpired("token")).thenReturn(false);
        when(jwtService.extractEmail("token")).thenReturn("user@test.com");
        when(jwtService.extractUserId("token")).thenReturn(userId.toString());
        when(refreshTokenService.validateRefreshToken(userId.toString(), "token")).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Refresh token invalid");
    }

    @Test
    void refresh_whenUserInactive_deletesTokenAndThrows() {
        UUID userId = UUID.randomUUID();
        User inactiveUser = buildUser(userId, false, false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(cookieUtils.extractRefreshTokenFromCookie(request)).thenReturn("token");
        when(jwtService.isTokenExpired("token")).thenReturn(false);
        when(jwtService.extractEmail("token")).thenReturn("user@test.com");
        when(jwtService.extractUserId("token")).thenReturn(userId.toString());
        when(refreshTokenService.validateRefreshToken(userId.toString(), "token")).thenReturn(true);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(inactiveUser));

        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("inactive");

        verify(refreshTokenService).deleteRefreshToken(userId.toString());
    }

    @Test
    void refresh_withValidToken_returnsNewTokensAndStoresNewRefreshToken() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, true, false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(cookieUtils.extractRefreshTokenFromCookie(request)).thenReturn("old-rt");
        when(jwtService.isTokenExpired("old-rt")).thenReturn(false);
        when(jwtService.extractEmail("old-rt")).thenReturn("user@test.com");
        when(jwtService.extractUserId("old-rt")).thenReturn(userId.toString());
        when(refreshTokenService.validateRefreshToken(userId.toString(), "old-rt")).thenReturn(true);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user)).thenReturn("new-at");
        when(jwtService.generateRefreshToken(user)).thenReturn("new-rt");

        LoginResponse response = authService.refresh(request);

        assertThat(response.getToken()).isEqualTo("new-at");
        assertThat(response.getRefreshToken()).isEqualTo("new-rt");
        verify(refreshTokenService).storeRefreshToken(userId.toString(), "new-rt");
    }

    // --- logout ---

    @Test
    void logout_whenCookiePresent_deletesRefreshToken() {
        UUID userId = UUID.randomUUID();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(cookieUtils.extractRefreshTokenFromCookie(request)).thenReturn("rt");
        when(jwtService.extractUserId("rt")).thenReturn(userId.toString());

        authService.logout(request);

        verify(refreshTokenService).deleteRefreshToken(userId.toString());
    }

    @Test
    void logout_whenNoCookie_doesNotAttemptDelete() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(cookieUtils.extractRefreshTokenFromCookie(request)).thenReturn(null);

        authService.logout(request);

        verify(refreshTokenService, never()).deleteRefreshToken(any());
    }

    // --- changePassword ---

    @Test
    void changePassword_whenUserNotFound_throwsResourceNotFoundException() {
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());
        ChangePasswordRequest request = buildChangePasswordRequest("old", "newPass123");

        assertThatThrownBy(() -> authService.changePassword("ghost@test.com", request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void changePassword_whenCurrentPasswordWrong_throwsBadCredentialsException() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, true, true);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongOld", "hashed")).thenReturn(false);
        ChangePasswordRequest request = buildChangePasswordRequest("wrongOld", "newPass123");

        assertThatThrownBy(() -> authService.changePassword("user@test.com", request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Current password is incorrect");
    }

    @Test
    void changePassword_whenNewPasswordSameAsCurrent_throwsIllegalArgumentException() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, true, true);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("same", "hashed")).thenReturn(true);
        ChangePasswordRequest request = buildChangePasswordRequest("same", "same");

        assertThatThrownBy(() -> authService.changePassword("user@test.com", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void changePassword_success_clearsFlag_andReturnsNewTokens() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, true, true);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old", "hashed")).thenReturn(true);
        when(passwordEncoder.encode("newPass123")).thenReturn("newHash");
        when(jwtService.generateToken(user)).thenReturn("new-at");
        when(jwtService.generateRefreshToken(user)).thenReturn("new-rt");
        ChangePasswordRequest request = buildChangePasswordRequest("old", "newPass123");

        LoginResponse response = authService.changePassword("user@test.com", request);

        assertThat(response.isMustChangePassword()).isFalse();
        assertThat(response.getToken()).isEqualTo("new-at");
        verify(userRepository).save(user);
        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(user.getPasswordHash()).isEqualTo("newHash");
    }

    private ChangePasswordRequest buildChangePasswordRequest(String current, String newPass) {
        ChangePasswordRequest req = new ChangePasswordRequest();
        ReflectionTestUtils.setField(req, "currentPassword", current);
        ReflectionTestUtils.setField(req, "newPassword", newPass);
        return req;
    }
}
