package com.amalitech.labresultsvalidator.domain.reference;

import com.amalitech.labresultsvalidator.support.AbstractIntegrationTest;
import com.amalitech.labresultsvalidator.support.CohortFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Re-verifies two RTM rows whose Fail verdicts predated the August fix rounds.
 *
 * <p>Both were written up when the behaviour really was wrong. Both were then fixed and neither
 * verdict was revisited, so the board carried defects that no longer existed. Reading the code
 * suggested they were fixed; this makes it evidence.
 */
@AutoConfigureMockMvc
class CohortReferenceScopeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void signIn() throws Exception {
        String email = "ref.admin." + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        jdbc.update("INSERT INTO users (id, email, password_hash, role, is_active, must_change_password) "
                + "VALUES (?, ?, ?, 'admin', true, false)",
            UUID.randomUUID(), email, passwordEncoder.encode("Correct@Horse1"));
        String body = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Correct@Horse1"}""".formatted(email)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        token = objectMapper.readTree(body).path("data").path("token").asText();
    }

    @Test
    @DisplayName("A6 AC1 / FND-40 — a cohort's reference data shows only that cohort's people")
    void theCohortReferencePayloadIsScopedToThatCohort() throws Exception {
        CohortFixture mine = CohortFixture.create(jdbc, CohortFixture.uniqueName("Scope Mine"),
            "scope-mine", "Adjoa Mensimah-Tetteh", "adjoa.scope@example.test");
        mine.addLearner("Kojo Boateng-Asare", "kojo.scope@example.test");

        // A second cohort with its own people. The original defect was that this cohort's
        // instructors appeared on the first one's page — the endpoint listed every contact in the
        // system, so an admin could not tell whose reference data they were looking at.
        CohortFixture theirs = CohortFixture.create(jdbc, CohortFixture.uniqueName("Scope Theirs"),
            "scope-theirs", "Yaw Ntim-Gyakari", "yaw.scope@example.test");
        theirs.addLearner("Afia Serwaa-Donkor", "afia.scope@example.test");

        String body = mockMvc.perform(get("/api/v1/cohorts/{id}/reference", mine.cohortId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).path("data");
        assertThat(data.toString()).contains("Adjoa Mensimah-Tetteh", "Kojo Boateng-Asare");
        assertThat(data.toString())
            .as("the other cohort's people must not appear here")
            .doesNotContain("Yaw Ntim-Gyakari")
            .doesNotContain("Afia Serwaa-Donkor");
    }

    @Test
    @DisplayName("B6 AC4 / FND-41 — a duplicate instructor name can no longer exist to break a run")
    void duplicateInstructorNamesAreImpossible() {
        String name = "Kwabena Duplicate-Test " + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO instructor_contacts (id, email, full_name, is_active) "
            + "VALUES (?, ?, ?, true)", UUID.randomUUID(), "dupe.a." + name + "@example.test", name);

        // The original failure was a query returning two rows and throwing, which failed the whole
        // workbook while the UI reported a clean run. The fix was structural rather than defensive:
        // V35 de-duplicated existing contacts and added a unique index on LOWER(full_name), so the
        // state that caused the throw can no longer be reached.
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO instructor_contacts (id, email, full_name, is_active) VALUES (?, ?, ?, true)",
            UUID.randomUUID(), "dupe.b." + name + "@example.test", name.toUpperCase()))
            .hasMessageContaining("uq_instructor_contacts_full_name");
    }
}
