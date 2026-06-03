package com.amalitech.labresultsvalidator.security;

import com.amalitech.labresultsvalidator.domain.enums.UserRole;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;

    // 32 bytes of 0x01, Base64-encoded — valid HMAC-SHA256 key for tests only
    private static final String TEST_SECRET = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
    private static final long ONE_DAY_MS = 86_400_000L;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", TEST_SECRET);
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", ONE_DAY_MS);
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
    void generateToken_producesNonBlankToken() {
        String token = jwtService.generateToken(buildUser("user@test.com", UserRole.ADMIN));
        assertThat(token).isNotBlank();
    }

    @Test
    void extractEmail_returnsCorrectSubject() {
        User user = buildUser("user@test.com", UserRole.ADMIN);
        String token = jwtService.generateToken(user);
        assertThat(jwtService.extractEmail(token)).isEqualTo("user@test.com");
    }

    @Test
    void extractRole_returnsRoleEmbeddedInToken() {
        String token = jwtService.generateToken(buildUser("user@test.com", UserRole.INSTRUCTOR));
        assertThat(jwtService.extractRole(token)).isEqualTo("INSTRUCTOR");
    }

    @Test
    void extractRole_returnsSuperAdminRole() {
        String token = jwtService.generateToken(buildUser("sa@test.com", UserRole.SUPER_ADMIN));
        assertThat(jwtService.extractRole(token)).isEqualTo("SUPER_ADMIN");
    }

    @Test
    void isTokenValid_returnsTrueForMatchingUser() {
        User user = buildUser("user@test.com", UserRole.ADMIN);
        String token = jwtService.generateToken(user);
        assertThat(jwtService.isTokenValid(token, user)).isTrue();
    }

    @Test
    void isTokenValid_returnsFalseWhenEmailDoesNotMatch() {
        User tokenOwner = buildUser("owner@test.com", UserRole.ADMIN);
        User otherUser = buildUser("other@test.com", UserRole.INSTRUCTOR);
        String token = jwtService.generateToken(tokenOwner);
        assertThat(jwtService.isTokenValid(token, otherUser)).isFalse();
    }

    @Test
    void extractEmail_throwsForExpiredToken() {
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", -1000L);
        User user = buildUser("user@test.com", UserRole.ADMIN);
        String expiredToken = jwtService.generateToken(user);
        assertThatThrownBy(() -> jwtService.extractEmail(expiredToken))
                .isInstanceOf(Exception.class);
    }
}
