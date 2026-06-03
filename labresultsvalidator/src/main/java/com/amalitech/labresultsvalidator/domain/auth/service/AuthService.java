package com.amalitech.labresultsvalidator.domain.auth.service;

import com.amalitech.labresultsvalidator.common.utils.CookieUtils;
import com.amalitech.labresultsvalidator.domain.auth.dto.LoginRequest;
import com.amalitech.labresultsvalidator.domain.auth.dto.LoginResponse;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.domain.user.repository.UserRepository;
import com.amalitech.labresultsvalidator.security.JwtService;
import com.amalitech.labresultsvalidator.security.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;
    private final CookieUtils cookieUtils;

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
}