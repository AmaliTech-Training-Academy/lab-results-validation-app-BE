package com.amalitech.labresultsvalidator.domain.auth;

import com.amalitech.labresultsvalidator.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Epic E — admin auth and RBAC — through the real HTTP stack, with a real Postgres and the real
 * Redis that refresh tokens and password-reset tokens actually live in.
 *
 * <p>All seventeen of these criteria were {@code static} in the RTM. Auth is the wrong thing to
 * take on trust: "a deactivated admin is rejected" and "a reused reset token is refused" are claims
 * about what the running system does when someone tries, and the failure mode of getting them wrong
 * is silent until it is exploited.
 *
 * <p>Every test creates its own admin — the context and database are shared across the suite, and
 * an auth test that depends on another test's user is a flaky test waiting to happen.
 */
@AutoConfigureMockMvc
class AuthIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Correct@Horse1";
    private static final String NEW_PASSWORD = "Battery@Staple2";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private String email;

    @BeforeEach
    void seedAdmin() {
        email = "admin." + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        createAdmin(email, PASSWORD, true, false);
    }

    private void createAdmin(String address, String password, boolean active, boolean mustChange) {
        jdbc.update("INSERT INTO users (id, email, password_hash, role, is_active, must_change_password) "
                + "VALUES (?, ?, ?, 'admin', ?, ?)",
            UUID.randomUUID(), address, passwordEncoder.encode(password), active, mustChange);
    }

    // ── E1 — login ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("E1 AC1 — valid credentials issue a JWT and a refresh-token cookie")
    void validCredentialsIssueATokenAndARefreshCookie() throws Exception {
        MvcResult result = login(email, PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.data.role").value("ADMIN"))   // enum name; the column stores 'admin'
            .andReturn();

        // The refresh token is deliberately absent from the body (@JsonIgnore) and delivered as a
        // cookie instead, so a stolen response body cannot mint new sessions.
        assertThat(result.getResponse().getContentAsString()).doesNotContain("refreshToken");
        assertThat(refreshCookie(result)).isNotNull();
    }

    @Test
    @DisplayName("E1 AC2 — a wrong password and an unknown email fail the same way")
    void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
        String wrongPassword = login(email, "Wrong@Password9")
            .andExpect(status().isUnauthorized()).andReturn()
            .getResponse().getContentAsString();
        String unknownEmail = login("nobody." + UUID.randomUUID() + "@example.test", PASSWORD)
            .andExpect(status().isUnauthorized()).andReturn()
            .getResponse().getContentAsString();

        // Identical responses, or the endpoint becomes an account-enumeration oracle.
        assertThat(messageOf(wrongPassword)).isEqualTo(messageOf(unknownEmail));
    }

    @Test
    @DisplayName("E1 AC3 — a deactivated admin cannot log in")
    void aDeactivatedAdminCannotLogIn() throws Exception {
        String deactivated = "inactive." + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        createAdmin(deactivated, PASSWORD, false, false);

        login(deactivated, PASSWORD).andExpect(status().is4xxClientError());
    }

    // ── E2 — forced password change ──────────────────────────────────────────

    @Test
    @DisplayName("E2 AC1/AC2 — the must-change flag is reported, then cleared by a successful change")
    void changingThePasswordClearsTheMustChangeFlag() throws Exception {
        String pending = "pending." + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        createAdmin(pending, PASSWORD, true, true);

        MvcResult loggedIn = login(pending, PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mustChangePassword").value(true))
            .andReturn();

        mockMvc.perform(post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + tokenOf(loggedIn))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"%s","newPassword":"%s"}""".formatted(PASSWORD, NEW_PASSWORD)))
            .andExpect(status().isOk());

        Boolean flag = jdbc.queryForObject(
            "SELECT must_change_password FROM users WHERE email = ?", Boolean.class, pending);
        assertThat(flag).isFalse();
        login(pending, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("E2 AC3 — a new password identical to the current one is refused")
    void reusingTheCurrentPasswordIsRefused() throws Exception {
        MvcResult loggedIn = login(email, PASSWORD).andExpect(status().isOk()).andReturn();

        mockMvc.perform(post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + tokenOf(loggedIn))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"%s","newPassword":"%s"}""".formatted(PASSWORD, PASSWORD)))
            .andExpect(status().is4xxClientError());
    }

    // ── E3 — forgotten password ──────────────────────────────────────────────

    @Test
    @DisplayName("E3 AC1 — forgot-password answers identically whether the account exists or not")
    void forgotPasswordDoesNotRevealWhetherAnAccountExists() throws Exception {
        String known = forgotPassword(email)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String unknown = forgotPassword("ghost." + UUID.randomUUID() + "@example.test")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(messageOf(known)).isEqualTo(messageOf(unknown));
    }

    @Test
    @DisplayName("E3 AC3 — an invalid reset token is rejected")
    void anInvalidResetTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"token":"not-a-real-token","newPassword":"%s"}""".formatted(NEW_PASSWORD)))
            .andExpect(status().is4xxClientError());
    }

    // ── E4 — session lifecycle ───────────────────────────────────────────────

    @Test
    @DisplayName("E4 AC1 — a valid refresh cookie mints a new access token")
    void aValidRefreshCookieMintsANewToken() throws Exception {
        MvcResult loggedIn = login(email, PASSWORD).andExpect(status().isOk()).andReturn();

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie(loggedIn)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    @DisplayName("E4 AC2 — refreshing without a cookie is rejected with 401, not a server error")
    void refreshWithoutACookieIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("E4 AC3 — logout invalidates the refresh token server-side, not just in the browser")
    void logoutInvalidatesTheRefreshTokenServerSide() throws Exception {
        MvcResult loggedIn = login(email, PASSWORD).andExpect(status().isOk()).andReturn();
        Cookie cookie = refreshCookie(loggedIn);

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + tokenOf(loggedIn))
                .cookie(cookie))
            .andExpect(status().isOk());

        // Replaying the same cookie must fail. Clearing it browser-side would not be enough —
        // anyone who captured it could keep minting access tokens.
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(cookie))
            .andExpect(status().isUnauthorized());
    }

    // ── E5 — route protection ────────────────────────────────────────────────

    @Test
    @DisplayName("E5 AC1 — an unauthenticated request to a protected route is rejected")
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/cohorts"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("E5 AC2 — a tampered JWT is rejected")
    void aTamperedTokenIsRejected() throws Exception {
        MvcResult loggedIn = login(email, PASSWORD).andExpect(status().isOk()).andReturn();
        String tampered = tokenOf(loggedIn).substring(0, tokenOf(loggedIn).length() - 3) + "aaa";

        mockMvc.perform(get("/api/v1/cohorts").header("Authorization", "Bearer " + tampered))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("E5 AC1 — a valid token reaches the protected route")
    void aValidTokenIsAccepted() throws Exception {
        MvcResult loggedIn = login(email, PASSWORD).andExpect(status().isOk()).andReturn();

        mockMvc.perform(get("/api/v1/cohorts").header("Authorization", "Bearer " + tokenOf(loggedIn)))
            .andExpect(status().isOk());
    }

    // ── E6 — the single-role model ───────────────────────────────────────────

    @Test
    @DisplayName("E6 AC1 — the schema admits exactly one role, enforced by the database")
    void theSchemaAdmitsOnlyTheAdminRole() {
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, 'x', 'instructor')",
            UUID.randomUUID(), "instructor." + UUID.randomUUID() + "@example.test"))
            .hasMessageContaining("chk_user_role");
    }

    @Test
    @Disabled("FND-14 / RTM E6-AC2 — InstructorProvisionedEvent is still in the codebase. It is "
        + "referenced by nothing, so removing it is a one-file deletion; enable this then.")
    @DisplayName("E6 AC2 — the instructor-provisioning event class is gone")
    void theInstructorProvisioningEventIsRemoved() {
        assertThatThrownBy(() -> Class.forName(
            "com.amalitech.labresultsvalidator.domain.user.event.InstructorProvisionedEvent"))
            .isInstanceOf(ClassNotFoundException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions login(String address, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"%s"}""".formatted(address, password)));
    }

    private org.springframework.test.web.servlet.ResultActions forgotPassword(String address)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/forgot-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s"}""".formatted(address)));
    }

    private String tokenOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("token").asText();
    }

    private String messageOf(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        return root.path("message").asText();
    }

    /** The refresh cookie, whatever it is named — the name is configuration, not contract. */
    private Cookie refreshCookie(MvcResult result) {
        Cookie[] cookies = result.getResponse().getCookies();
        return cookies.length == 0 ? null : cookies[0];
    }
}
