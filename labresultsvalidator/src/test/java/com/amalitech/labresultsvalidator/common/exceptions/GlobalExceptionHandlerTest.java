package com.amalitech.labresultsvalidator.common.exceptions;

import com.amalitech.labresultsvalidator.common.csv.MalformedCsvException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that database and transaction failures are dispatched to dedicated
 * handlers with meaningful, distinct messages, and that the generic
 * "An unexpected error occurred" response is reserved for truly unexpected errors.
 *
 * <p>Uses standalone MockMvc so it exercises Spring's real most-specific-match
 * exception dispatch without booting the full application context.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void handle_constraintViolation_returns409WithMeaningfulMessage() throws Exception {
        mockMvc.perform(get("/throw/constraint"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message", containsString("database constraint")));
    }

    @Test
    void handle_connectionFailure_returns503() throws Exception {
        mockMvc.perform(get("/throw/connection"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message", containsString("database is temporarily unavailable")));
    }

    @Test
    void handle_queryTimeout_returns503() throws Exception {
        mockMvc.perform(get("/throw/timeout"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message", containsString("database is temporarily unavailable")));
    }

    @Test
    void handle_transactionFailure_returns500WithRollbackMessage() throws Exception {
        mockMvc.perform(get("/throw/transaction"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message", containsString("rolled back")));
    }

    @Test
    void handle_otherDataAccessError_returns500WithDatabaseMessage() throws Exception {
        mockMvc.perform(get("/throw/data-access"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message", containsString("A database error occurred")));
    }

    @Test
    void handle_csvValidationFailure_returns422AndIsNotTreatedAsDbError() throws Exception {
        mockMvc.perform(get("/throw/csv"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.message", containsString("bad csv")));
    }

    @Test
    void handle_trulyUnexpectedError_returnsGenericMessage() throws Exception {
        mockMvc.perform(get("/throw/unexpected"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/throw/constraint")
        void constraint() {
            throw new DataIntegrityViolationException("could not execute statement; unique constraint");
        }

        @GetMapping("/throw/connection")
        void connection() {
            throw new DataAccessResourceFailureException("could not open JDBC connection");
        }

        @GetMapping("/throw/timeout")
        void timeout() {
            throw new QueryTimeoutException("statement timed out");
        }

        @GetMapping("/throw/transaction")
        void transaction() {
            throw new UnexpectedRollbackException("transaction silently rolled back");
        }

        @GetMapping("/throw/data-access")
        void dataAccess() {
            throw new InvalidDataAccessApiUsageException("some other data access failure");
        }

        @GetMapping("/throw/csv")
        void csv() {
            throw new MalformedCsvException("bad csv");
        }

        @GetMapping("/throw/unexpected")
        void unexpected() {
            throw new RuntimeException("kaboom");
        }
    }
}
