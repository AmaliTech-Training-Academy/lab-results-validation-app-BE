package com.amalitech.labresultsvalidator.domain.sync;

import com.amalitech.labresultsvalidator.support.AbstractIntegrationTest;
import com.amalitech.labresultsvalidator.support.CohortFixture;
import com.amalitech.labresultsvalidator.support.GradingWorkbookBuilder;
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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Main path #5 — a duplicate is held, resolved, and the right grade lands.
 *
 * <p>The last main path without executed coverage, and the one carrying two findings. FND-49's
 * original defect was that a duplicate produced <em>two independently-decidable rows</em>, so two
 * contradictory decisions could both be accepted and the outcome depended on click order — a grade
 * was silently discarded. The rework made it one conflict holding both candidates, decided once, by
 * index. That was verified live in August and has never been covered by a test.
 *
 * <p>Driven through the real HTTP endpoint rather than the service, for two reasons: resolution
 * reads the acting admin from the security context, so a service-level call would need that plumbed
 * artificially; and the endpoint's own request validation is part of what protects the feature.
 */
@AutoConfigureMockMvc
class ConflictResolutionIntegrationTest extends AbstractIntegrationTest {

    private static final String LAB = "Provisioning a Virtual Network";
    private static final String LEARNER = "Adjoa Nyantakyi-Boafo";
    private static final int KEPT_SCORE = 91;
    private static final int DISCARDED_SCORE = 62;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;

    private CohortFixture cohort;
    private String folderName;
    private String reviewer;
    private String token;

    @BeforeEach
    void seedCohortAndSignIn() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        folderName = "conflict-" + suffix;
        reviewer = "Nana Adjei-Mensah " + suffix;

        cohort = CohortFixture.create(jdbc, CohortFixture.uniqueName("Conflict Test Cohort"),
            folderName, reviewer, "nana." + suffix + "@example.test");
        cohort.addLab(LAB);
        cohort.addLearner(LEARNER, "adjoa." + suffix + "@example.test");

        String email = "conflict.admin." + suffix + "@example.test";
        jdbc.update("INSERT INTO users (id, email, password_hash, role, is_active, must_change_password) "
                + "VALUES (?, ?, ?, 'admin', true, false)",
            UUID.randomUUID(), email, passwordEncoder.encode("Correct@Horse1"));

        String body = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Correct@Horse1"}""".formatted(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        token = objectMapper.readTree(body).path("data").path("token").asText();
    }

    /** The same learner and lab twice on one sheet, with two different marks. */
    private void writeDuplicateRows() {
        GradingWorkbookBuilder.sheet("Module-1")
            .row(LocalDate.of(2026, 3, 2), LEARNER, reviewer, LAB, DISCARDED_SCORE)
            .row(LocalDate.of(2026, 3, 3), LEARNER, reviewer, LAB, KEPT_SCORE)
            .writeTo(fixtureRoot().resolve(folderName).resolve("Lab Scores")
                .resolve("Module 1 Grading.xlsx"));
    }

    // ── the queue ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("B10 — a duplicate produces ONE conflict holding BOTH candidates, not two rows")
    void aDuplicateProducesOneConflictWithBothCandidates() throws Exception {
        writeDuplicateRows();

        runSyncAndWait(cohort.cohortId);

        List<Map<String, Object>> conflicts = pendingConflicts();
        // One decision to make, not two. Two independently-decidable rows was FND-49's defect: it let
        // contradictory decisions both be accepted, and the surviving grade depended on click order.
        assertThat(conflicts).hasSize(1);

        JsonNode candidates = candidatesOf(conflicts.get(0));
        assertThat(candidates).hasSize(2);
        // Compared as ints: the stored scale is 2, so BigDecimal.equals("62") vs "62.00" is false
        // even though the values match. Scale is a storage detail, not part of the claim.
        assertThat(scoresIn(candidates)).containsExactlyInAnyOrder(DISCARDED_SCORE, KEPT_SCORE);

        // Nothing is committed while the decision is outstanding.
        assertThat(committedScores()).isEmpty();
    }

    @Test
    @DisplayName("B10 / FND-47 — each candidate carries the marks and the row it came from")
    void eachCandidateIsLegibleWithoutReadingRawJson() throws Exception {
        writeDuplicateRows();

        runSyncAndWait(cohort.cohortId);

        JsonNode candidates = candidatesOf(pendingConflicts().get(0));
        for (JsonNode candidate : candidates) {
            // FND-47 was that the queue showed database ids and a JSON blob, so the two options
            // looked identical and an admin could not tell them apart. The payload has to carry
            // enough for a human to choose.
            assertThat(candidate.path("score").isMissingNode()).isFalse();
            assertThat(candidate.path("rowNum").asInt(-1))
                .as("the spreadsheet row is what lets an admin go and look").isPositive();
            assertThat(candidate.path("sheetName").asText())
                .as("and which sheet it was on").isNotBlank();
            assertThat(candidate.path("nspName").asText())
                .as("and whose grade it is").isNotBlank();
        }
    }

    // ── the decision ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("B10 / FND-49 — the chosen mark lands and the discarded one is recorded, not lost")
    void theChosenMarkLandsAndTheDiscardedOneIsKept() throws Exception {
        writeDuplicateRows();
        runSyncAndWait(cohort.cohortId);
        Map<String, Object> conflict = pendingConflicts().get(0);
        int keepIndex = indexOfScore(candidatesOf(conflict), KEPT_SCORE);

        resolve(conflict.get("id"), "KEEP_INCOMING", keepIndex, "keeping the later mark")
            .andExpect(status().isOk());

        // The mark the admin picked is the one stored.
        assertThat(committedScoreValues()).containsExactly(KEPT_SCORE);

        // And the one they did not pick is still recoverable. A silently discarded grade was the
        // serious half of FND-49 — a mark disappearing with no trace is worse than a loud failure.
        String history = gradeHistory();
        assertThat(history).contains(String.valueOf(DISCARDED_SCORE));
    }

    @Test
    @DisplayName("B10 — a second decision on the same conflict is refused, not silently applied")
    void aSecondDecisionIsRefused() throws Exception {
        writeDuplicateRows();
        runSyncAndWait(cohort.cohortId);
        Map<String, Object> conflict = pendingConflicts().get(0);
        int keepIndex = indexOfScore(candidatesOf(conflict), KEPT_SCORE);

        resolve(conflict.get("id"), "KEEP_INCOMING", keepIndex, null).andExpect(status().isOk());

        // Refusing the second decision is what makes the outcome independent of click order.
        resolve(conflict.get("id"), "KEEP_INCOMING", indexOfScore(candidatesOf(conflict), DISCARDED_SCORE), null)
            .andExpect(status().isConflict());

        assertThat(committedScoreValues()).containsExactly(KEPT_SCORE);
    }

    @Test
    @DisplayName("B10 — KEEP_INCOMING without naming a candidate is rejected, never guessed")
    void keepIncomingWithoutAChoiceIsRejected() throws Exception {
        writeDuplicateRows();
        runSyncAndWait(cohort.cohortId);
        Map<String, Object> conflict = pendingConflicts().get(0);

        // Two candidates and no index means the server would have to guess which grade to keep.
        // Guessing is the behaviour that lost a mark in the first place.
        resolve(conflict.get("id"), "KEEP_INCOMING", null, null)
            .andExpect(status().is4xxClientError());

        assertThat(pendingConflicts()).hasSize(1);
        assertThat(committedScores()).isEmpty();
    }

    @Test
    @DisplayName("B10 — rejecting a duplicate commits neither mark and closes the conflict")
    void rejectingCommitsNeitherMark() throws Exception {
        writeDuplicateRows();
        runSyncAndWait(cohort.cohortId);

        resolve(pendingConflicts().get(0).get("id"), "REJECT", null, "both look wrong")
            .andExpect(status().isOk());

        assertThat(committedScores()).isEmpty();
        assertThat(pendingConflicts()).isEmpty();
    }

    // ── FND-48 — it must stay resolved ───────────────────────────────────────

    @Test
    @DisplayName("FND-48 — a resolved duplicate does not reopen when the file is synced again")
    void aResolvedConflictDoesNotReopenOnResync() throws Exception {
        writeDuplicateRows();
        runSyncAndWait(cohort.cohortId);
        Map<String, Object> conflict = pendingConflicts().get(0);
        resolve(conflict.get("id"), "KEEP_INCOMING",
            indexOfScore(candidatesOf(conflict), KEPT_SCORE), null).andExpect(status().isOk());

        // Same bytes, so the run should short-circuit and raise nothing.
        runSyncAndWait(cohort.cohortId);

        assertThat(pendingConflicts())
            .as("re-raising a decided conflict would make the queue impossible to clear")
            .isEmpty();
        assertThat(committedScoreValues()).containsExactly(KEPT_SCORE);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions resolve(
            Object conflictId, String action, Integer chosenRowIndex, String note) throws Exception {
        StringBuilder json = new StringBuilder("{\"action\":\"").append(action).append('"');
        if (chosenRowIndex != null) {
            json.append(",\"chosenRowIndex\":").append(chosenRowIndex);
        }
        if (note != null) {
            json.append(",\"note\":\"").append(note).append('"');
        }
        json.append('}');

        return mockMvc.perform(patch("/api/v1/cohorts/{id}/conflicts/{conflictId}/resolve",
                cohort.cohortId, conflictId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.toString()));
    }

    /**
     * Conflicts for this cohort. Scoped through {@code ingestion_runs} because
     * {@code ingestion_conflicts.cohort_id} was dropped in V32.
     */
    private List<Map<String, Object>> pendingConflicts() {
        return jdbc.queryForList(
            "SELECT c.id, c.incoming_payload_json, c.status FROM ingestion_conflicts c "
                + "JOIN ingestion_runs r ON r.id = c.ingestion_run_id "
                + "WHERE r.cohort_id = ? AND c.status = 'PENDING'", cohort.cohortId);
    }

    private JsonNode candidatesOf(Map<String, Object> conflict) throws Exception {
        JsonNode payload = objectMapper.readTree(String.valueOf(conflict.get("incoming_payload_json")));
        return payload.isArray() ? payload : payload.path("candidates");
    }

    private List<Integer> scoresIn(JsonNode candidates) {
        return candidates.findValues("score").stream()
            .map(n -> new BigDecimal(n.asText()).intValue()).toList();
    }

    /**
     * The {@code chosenRowIndex} to send for a given mark.
     *
     * <p>Worth knowing: the stored payload carries <em>no</em> index field. The index is the
     * candidate's **position in the stored array**, assigned when the payload is read
     * (`ConflictPayloadCodec.toCandidate(int index, ...)`). So the whole resolution contract rests on
     * that array order staying stable between the read that shows an admin the choices and the write
     * that acts on it. Reading `index` off the JSON — as this test first did — silently yields 0 and
     * keeps the wrong grade.
     */
    private int indexOfScore(JsonNode candidates, int score) {
        for (int i = 0; i < candidates.size(); i++) {
            if (new BigDecimal(candidates.get(i).path("score").asText()).intValue() == score) {
                return i;
            }
        }
        throw new AssertionError("No candidate carried the score " + score + " in " + candidates);
    }

    private List<Integer> committedScoreValues() {
        return committedScores().stream().map(BigDecimal::intValue).toList();
    }

    private List<BigDecimal> committedScores() {
        return jdbc.queryForList(
            "SELECT score FROM lab_results WHERE learner_id IN "
                + "(SELECT id FROM learners WHERE cohort_id = ?)", BigDecimal.class, cohort.cohortId);
    }

    private String gradeHistory() {
        return String.valueOf(jdbc.queryForList(
            "SELECT field_name, old_value, new_value, reason FROM lab_reference_audit_log "
                + "WHERE table_name = 'lab_results' AND record_id IN "
                + "(SELECT id FROM lab_results WHERE learner_id IN "
                + "(SELECT id FROM learners WHERE cohort_id = ?))", cohort.cohortId));
    }
}
