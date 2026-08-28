package com.amalitech.labresultsvalidator.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // SSE EventSource in browsers cannot send custom headers; fall back to ?token= query param.
        final String jwt;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwt = authHeader.substring(7);
        } else {
            String tokenParam = request.getParameter("token");
            if (tokenParam != null && !tokenParam.isBlank()) {
                jwt = tokenParam;
            } else {
                filterChain.doFilter(request, response);
                return;
            }
        }
        final String userEmail;

        try {
            userEmail = jwtService.extractEmail(jwt);
        } catch (JwtException | IllegalArgumentException e) {
            // Expired/malformed/unsigned token, or JJWT rejecting a null/empty token string —
            // all genuinely "not a valid token." Quiet by design; this fires on every anonymous
            // request with a stale token and would otherwise flood the logs.
            LOG.debug("[auth] rejected token on {} {}: {}", request.getMethod(), request.getRequestURI(), e.toString());
            filterChain.doFilter(request, response);
            return;
        } catch (Exception e) {
            // NOT a bad-token condition — a bug in claims extraction itself. Still fail open
            // (a filter must never 500 the whole app over this — Spring Security's downstream
            // authorization correctly rejects the still-unauthenticated request on a protected
            // endpoint), but log loudly so a systemic issue here doesn't go completely unnoticed,
            // unlike before when this branch was fully silent.
            LOG.error("[auth] unexpected error extracting claims on {} {}: {}",
                request.getMethod(), request.getRequestURI(), e.getMessage(), e);
            filterChain.doFilter(request, response);
            return;
        }

        if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

                if (jwtService.isTokenValid(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            } catch (UsernameNotFoundException ex) {
                // The user behind this (still-valid, unexpired) token was deleted after it was
                // issued. This filter runs before ExceptionTranslationFilter, so letting this
                // propagate would bypass SecurityConfig's JSON authenticationEntryPoint and hit the
                // container's default error page instead. Leaving no authentication set here means
                // the request falls through to the normal "authenticated required" 401 JSON response.
                LOG.debug("JWT referenced an unknown user: {}", ex.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}
