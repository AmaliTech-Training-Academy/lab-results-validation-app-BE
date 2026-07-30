package com.amalitech.labresultsvalidator.infrastructure.graph;

import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.microsoft.kiota.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Retries transient Microsoft Graph failures with backoff, honouring {@code Retry-After}
 * when the service supplies it (B4 AC4).
 *
 * <p>The Graph SDK surfaces HTTP failures as {@link ApiException}, which carries the status
 * code and response headers. Callers must therefore route calls through here <em>before</em>
 * wrapping anything in {@link GraphAccessException} — once wrapped, the status and the
 * {@code Retry-After} header are gone and no retry decision is possible.
 */
@Component
public class GraphRetryExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(GraphRetryExecutor.class);

    private static final String RETRY_AFTER = "Retry-After";

    /**
     * 429 is Graph's throttle signal and the 5xx family covers transient server faults.
     *
     * <p>401 is included because an access token that expires mid-run surfaces here once and
     * the SDK's auth provider acquires a fresh one before the next attempt. The cost is that a
     * genuinely bad credential burns the whole attempt budget — a few seconds — before failing
     * with a clear message, which is an acceptable trade for surviving token expiry on a long
     * Monday sync (risk R-5a).
     */
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(401, 429, 500, 502, 503, 504);

    private final GraphRetryProperties props;
    private final Sleeper sleeper;

    public GraphRetryExecutor(GraphRetryProperties props, Sleeper sleeper) {
        this.props = props;
        this.sleeper = sleeper;
    }

    /**
     * Runs {@code call}, retrying transient failures until the attempt or wait budget runs out.
     *
     * @param operation human-readable description used in log lines and the final error
     * @param call      the Graph invocation; may throw any {@link RuntimeException}
     * @param <T>       the call's return type
     * @return the call's result
     * @throws GraphAccessException when the call fails non-transiently, or every retry is spent
     */
    public <T> T execute(String operation, Supplier<T> call) throws GraphAccessException {
        int attempts = Math.max(1, props.maxAttempts());
        long totalWaited = 0L;
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException ex) {
                lastFailure = ex;

                if (attempt >= attempts || !isRetryable(ex)) {
                    break;
                }

                long delay = nextDelayMillis(ex, attempt);
                if (totalWaited + delay > props.maxTotalWaitMillis()) {
                    LOG.warn("[graph-retry] {} — wait budget spent ({} ms), giving up", operation, totalWaited);
                    break;
                }

                LOG.warn("[graph-retry] {} failed on attempt {}/{} ({}), retrying in {} ms",
                    operation, attempt, attempts, describe(ex), delay);

                if (!sleepQuietly(delay)) {
                    LOG.warn("[graph-retry] {} — interrupted while backing off, abandoning retries", operation);
                    break;
                }
                totalWaited += delay;
            }
        }

        throw new GraphAccessException(
            "Graph call failed (" + operation + "): " + describe(lastFailure), lastFailure);
    }

    /** Throttling, transient server faults and network faults are worth another attempt. */
    private boolean isRetryable(RuntimeException ex) {
        if (ex instanceof ApiException api && RETRYABLE_STATUS.contains(api.getResponseStatusCode())) {
            return true;
        }
        return hasIoCause(ex);
    }

    private boolean hasIoCause(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof IOException) {
                return true;
            }
            if (cursor.getCause() == cursor) {
                break;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * A server-supplied {@code Retry-After} always wins — it reflects when Graph will actually
     * serve us again. Absent that, back off exponentially with half-jitter so a fleet of
     * workbooks throttled together doesn't retry in lockstep.
     */
    private long nextDelayMillis(RuntimeException ex, int attempt) {
        Long retryAfter = retryAfterMillis(ex);
        if (retryAfter != null) {
            return Math.min(retryAfter, props.maxRetryAfterMillis());
        }

        long exponential = props.initialBackoffMillis() << Math.min(attempt - 1, 20);
        long capped = Math.min(exponential, props.maxBackoffMillis());
        long half = capped / 2;
        return half + ThreadLocalRandom.current().nextLong(half + 1);
    }

    /** Reads {@code Retry-After} in both permitted forms: delta-seconds, or an HTTP date. */
    private Long retryAfterMillis(RuntimeException ex) {
        if (!(ex instanceof ApiException api) || api.getResponseHeaders() == null) {
            return null;
        }
        Set<String> values = api.getResponseHeaders().get(RETRY_AFTER);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String raw = values.iterator().next();
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String value = raw.trim();
        try {
            return Math.max(0L, Long.parseLong(value) * 1000L);
        } catch (NumberFormatException notDeltaSeconds) {
            return httpDateToMillis(value);
        }
    }

    private Long httpDateToMillis(String value) {
        try {
            ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            long millis = Duration.between(ZonedDateTime.now(when.getZone()), when).toMillis();
            return Math.max(0L, millis);
        } catch (DateTimeParseException ex) {
            LOG.debug("Ignoring unparseable Retry-After value '{}'", value);
            return null;
        }
    }

    /** @return false if the thread was interrupted, in which case retries must stop */
    private boolean sleepQuietly(long millis) {
        try {
            sleeper.sleep(millis);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String describe(Throwable ex) {
        if (ex == null) {
            return "no failure recorded";
        }
        if (ex instanceof ApiException api) {
            return "HTTP " + api.getResponseStatusCode() + " (" + ex.getClass().getSimpleName() + ")";
        }
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }
}
