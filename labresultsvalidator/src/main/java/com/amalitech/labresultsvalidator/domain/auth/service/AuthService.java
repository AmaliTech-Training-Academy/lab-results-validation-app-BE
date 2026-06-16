package com.amalitech.labresultsvalidator.domain.auth.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.service.EmailService;
import com.amalitech.labresultsvalidator.common.utils.CookieUtils;
import com.amalitech.labresultsvalidator.domain.auth.dto.ChangePasswordRequest;
import com.amalitech.labresultsvalidator.domain.auth.dto.ForgotPasswordRequest;
import com.amalitech.labresultsvalidator.domain.auth.dto.LoginRequest;
import com.amalitech.labresultsvalidator.domain.auth.dto.LoginResponse;
import com.amalitech.labresultsvalidator.domain.auth.dto.ResetPasswordRequest;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.amalitech.labresultsvalidator.security.JwtService;
import com.amalitech.labresultsvalidator.security.PasswordResetTokenService;
import com.amalitech.labresultsvalidator.security.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetTokenService passwordResetTokenService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CookieUtils cookieUtils;
    private final EmailService emailService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public LoginResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );

        User user = (User) authentication.getPrincipal();

        String accessToken = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        refreshTokenService.storeRefreshToken(
                user.getId().toString(),
                refreshToken
        );

        return LoginResponse.builder()
                .token(accessToken)
                .email(user.getEmail())
                .role(user.getRole().name())
                .mustChangePassword(user.isMustChangePassword())
                .refreshToken(refreshToken)
                .build();
    }

    public LoginResponse refresh(HttpServletRequest request) {
        String refreshToken = cookieUtils.extractRefreshTokenFromCookie(request);

        if (refreshToken == null) {
            throw new RuntimeException("Refresh token not found");
        }

        if (jwtService.isTokenExpired(refreshToken)) {
            throw new RuntimeException("Refresh token expired");
        }

        String email = jwtService.extractEmail(refreshToken);
        String userId = jwtService.extractUserId(refreshToken);

        if (!refreshTokenService.validateRefreshToken(userId, refreshToken)) {
            throw new RuntimeException("Refresh token invalid");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.isActive()) {
            refreshTokenService.deleteRefreshToken(userId);
            throw new RuntimeException("User account is inactive");
        }

        String newAccessToken = jwtService.generateToken(user);
        String newRefreshToken = jwtService.generateRefreshToken(user);

        refreshTokenService.storeRefreshToken(
                user.getId().toString(),
                newRefreshToken
        );

        return LoginResponse.builder()
                .token(newAccessToken)
                .email(user.getEmail())
                .role(user.getRole().name())
                .mustChangePassword(user.isMustChangePassword())
                .refreshToken(newRefreshToken)
                .build();
    }

    public void logout(HttpServletRequest request) {
        String refreshToken = cookieUtils.extractRefreshTokenFromCookie(request);

        if (refreshToken != null) {
            String userId = jwtService.extractUserId(refreshToken);
            refreshTokenService.deleteRefreshToken(userId);
        }
    }

    public LoginResponse changePassword(String userEmail, ChangePasswordRequest request) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }

        if (request.getNewPassword().equals(request.getCurrentPassword())) {
            throw new IllegalArgumentException("New password must differ from the current password");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);

        String newAccessToken = jwtService.generateToken(user);
        String newRefreshToken = jwtService.generateRefreshToken(user);
        refreshTokenService.storeRefreshToken(user.getId().toString(), newRefreshToken);

        return LoginResponse.builder()
                .token(newAccessToken)
                .email(user.getEmail())
                .role(user.getRole().name())
                .mustChangePassword(false)
                .refreshToken(newRefreshToken)
                .build();
    }

    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.getEmail()).ifPresent(user -> {
            String token = passwordResetTokenService.createToken(user.getEmail());
            String resetLink = frontendUrl + "/set-password?token=" + token;
            emailService.sendPasswordResetEmail(user.getEmail(), resetLink);
        });
    }

    public void resetPassword(ResetPasswordRequest request) {
        String email = passwordResetTokenService.getEmailForToken(request.getToken());

        if (email == null) {
            throw new IllegalArgumentException("Invalid or expired reset token");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);

        passwordResetTokenService.deleteToken(request.getToken());
    }
}