package com.amalitech.labresultsvalidator.domain.auth.controller;

import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import com.amalitech.labresultsvalidator.security.JwtAuthenticationFilter;
import com.amalitech.labresultsvalidator.security.JwtService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private User buildUser(String email, UserRole role) {
        return User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash("hashed")
                .role(role)
                .build();
    }

    @Test
    void doFilterInternal_withNoAuthHeader_continuesChainUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_withMalformedToken_continuesChainUnauthenticated() throws Exception {
        when(jwtService.extractEmail(anyString())).thenThrow(new RuntimeException("invalid token"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad.token.here");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_withValidToken_setsAuthenticationInContext() throws Exception {
        User user = buildUser("user@test.com", UserRole.ADMIN);

        when(jwtService.extractEmail(anyString())).thenReturn("user@test.com");
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(user);
        when(jwtService.isTokenValid(anyString(), any())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("user@test.com");
        assertThat(auth.getAuthorities()).anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    @Test
    void doFilterInternal_withInvalidToken_doesNotSetAuthentication() throws Exception {
        User user = buildUser("user@test.com", UserRole.ADMIN);

        when(jwtService.extractEmail(anyString())).thenReturn("user@test.com");
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(user);
        when(jwtService.isTokenValid(anyString(), any())).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer tampered.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
