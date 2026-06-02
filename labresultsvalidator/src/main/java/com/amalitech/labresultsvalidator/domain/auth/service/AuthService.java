package com.amalitech.labresultsvalidator.domain.auth.service;

import com.amalitech.labresultsvalidator.domain.auth.dto.LoginRequest;
import com.amalitech.labresultsvalidator.domain.auth.dto.LoginResponse;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.security.JwtService;
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

    public LoginResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(
                request.getEmail(),
                request.getPassword()
            )
        );

        User user = (User) authentication.getPrincipal();
        String token = jwtService.generateToken(user);

        return LoginResponse.builder()
                .token(token)
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }
}
