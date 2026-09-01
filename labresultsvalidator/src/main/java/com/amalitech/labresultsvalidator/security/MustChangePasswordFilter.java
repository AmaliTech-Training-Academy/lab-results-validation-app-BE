package com.amalitech.labresultsvalidator.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private static final String CHANGE_PASSWORD_PATH = "/api/v1/auth/change-password";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        if (CHANGE_PASSWORD_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Must accept the token the exact same way JwtAuthenticationFilter does (header, falling
        // back to ?token= for SSE) — otherwise a user forced to change a temporary/leaked password
        // could bypass this check entirely just by moving their token from the header to the query
        // string, on any endpoint.
        String authHeader = request.getHeader("Authorization");
        String jwt;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwt = authHeader.substring(7);
        } else {
            String tokenParam = request.getParameter("token");
            if (tokenParam == null || tokenParam.isBlank()) {
                filterChain.doFilter(request, response);
                return;
            }
            jwt = tokenParam;
        }
        boolean mustChange;
        try {
            mustChange = jwtService.extractMustChangePassword(jwt);
        } catch (JwtException | IllegalArgumentException e) {
            filterChain.doFilter(request, response);
            return;
        }

        if (mustChange) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                "{\"success\":false,\"message\":\"Password change required\",\"data\":null}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}
