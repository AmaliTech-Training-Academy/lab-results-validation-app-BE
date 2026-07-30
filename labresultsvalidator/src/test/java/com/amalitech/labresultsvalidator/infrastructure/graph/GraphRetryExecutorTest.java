package com.amalitech.labresultsvalidator.infrastructure.graph;

import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.microsoft.kiota.ApiException;
import com.microsoft.kiota.ResponseHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphRetryExecutorTest {

    private List<Long> sleeps;
    private GraphRetryExecutor executor;

    @BeforeEach
    void setUp() {
        sleeps = new ArrayList<>();
        executor = newExecutor(new GraphRetryProperties(4, 1000L, 30_000L, 120_000L, 180_000L));
    }

    private GraphRetryExecutor newExecutor(GraphRetryProperties props) {
        return new GraphRetryExecutor(props, millis -> sleeps.add(millis));
    }

    /** Builds the failure shape the Graph SDK throws, since ApiException's setters are protected. */
    private static ApiException apiError(int status, String retryAfter) {
        ApiException ex = new ApiException("graph said no") {
            @Override
            public int getResponseStatusCode() {
                return status;
            }

            @Override
            public ResponseHeaders getResponseHeaders() {
                ResponseHeaders headers = new ResponseHeaders();
                if (retryAfter != null) {
                    headers.add("Retry-After", retryAfter);
                }
                return headers;
            }
        };
        return ex;
    }

    @Test
    void returnsResultWithoutSleepingWhenTheFirstAttemptSucceeds() throws Exception {
        String result = executor.execute("get item", () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void honoursRetryAfterExpressedInSeconds() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = executor.execute("throttled call", () -> {
            if (calls.incrementAndGet() == 1) {
                throw apiError(429, "7");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        assertThat(sleeps).containsExactly(7_000L);
    }

    @Test
    void honoursRetryAfterExpressedAsAnHttpDate() throws Exception {
        String httpDate = ZonedDateTime.now().plusSeconds(30)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        AtomicInteger calls = new AtomicInteger();

        executor.execute("throttled call", () -> {
            if (calls.incrementAndGet() == 1) {
                throw apiError(429, httpDate);
            }
            return "ok";
        });

        // Allow slack for the clock advancing between building the header and reading it.
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(25_000L, 30_000L);
    }

    @Test
    void capsAnOversizedRetryAfterSoOneFileCannotStallTheRun() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        executor.execute("throttled call", () -> {
            if (calls.incrementAndGet() == 1) {
                throw apiError(429, "3600");
            }
            return "ok";
        });

        assertThat(sleeps).containsExactly(120_000L);
    }

    @Test
    void backsOffExponentiallyWhenNoRetryAfterHeaderIsPresent() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        executor.execute("flaky call", () -> {
            if (calls.incrementAndGet() < 3) {
                throw apiError(503, null);
            }
            return "ok";
        });

        // Half-jitter: each delay sits between half and all of the exponential target.
        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(0)).isBetween(500L, 1000L);
        assertThat(sleeps.get(1)).isBetween(1000L, 2000L);
    }

    @Test
    void retriesExpiredTokensSoALongRunSurvivesTokenExpiry() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = executor.execute("call with stale token", () -> {
            if (calls.incrementAndGet() == 1) {
                throw apiError(401, null);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void retriesNetworkFaultsSurfacedAsIoExceptions() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        executor.execute("download", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new UncheckedIOException(new IOException("connection reset"));
            }
            return "ok";
        });

        assertThat(calls).hasValue(2);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void failsImmediatelyOnANonTransientStatus() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("forbidden call", () -> {
            calls.incrementAndGet();
            throw apiError(403, null);
        }))
            .isInstanceOf(GraphAccessException.class)
            .hasMessageContaining("HTTP 403");

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void givesUpAfterTheAttemptBudgetAndReportsTheLastFailure() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("always throttled", () -> {
            calls.incrementAndGet();
            throw apiError(429, "1");
        }))
            .isInstanceOf(GraphAccessException.class)
            .hasMessageContaining("always throttled")
            .hasMessageContaining("HTTP 429");

        assertThat(calls).hasValue(4);
        assertThat(sleeps).hasSize(3);
    }

    @Test
    void stopsRetryingOnceTheTotalWaitBudgetWouldBeExceeded() {
        // A 10s cumulative budget cannot afford a second 8s Retry-After.
        executor = newExecutor(new GraphRetryProperties(5, 1000L, 30_000L, 120_000L, 10_000L));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("throttled hard", () -> {
            calls.incrementAndGet();
            throw apiError(429, "8");
        }))
            .isInstanceOf(GraphAccessException.class);

        assertThat(sleeps).containsExactly(8_000L);
        assertThat(calls).hasValue(2);
    }

    @Test
    void abandonsRetriesWhenInterruptedAndRestoresTheInterruptFlag() {
        GraphRetryExecutor interrupting = new GraphRetryExecutor(
            new GraphRetryProperties(4, 1000L, 30_000L, 120_000L, 180_000L),
            millis -> {
                throw new InterruptedException("shutting down");
            });

        assertThatThrownBy(() -> interrupting.execute("call during shutdown", () -> {
            throw apiError(503, null);
        }))
            .isInstanceOf(GraphAccessException.class);

        assertThat(Thread.interrupted()).isTrue();
    }
}
