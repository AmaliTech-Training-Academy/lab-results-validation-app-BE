package com.amalitech.labresultsvalidator.infrastructure.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backoff budget for transient Graph failures (B4 AC4).
 *
 * @param maxAttempts          total attempts including the first, so 4 means 1 try + 3 retries
 * @param initialBackoffMillis first retry delay when the response carries no {@code Retry-After}
 * @param maxBackoffMillis     ceiling on any single computed backoff
 * @param maxRetryAfterMillis  ceiling applied to a server-supplied {@code Retry-After}, so an
 *                             oversized value cannot stall a run
 * @param maxTotalWaitMillis   ceiling on cumulative sleeping across all retries for one call
 */
@ConfigurationProperties(prefix = "labgate.graph.retry")
public record GraphRetryProperties(
    int maxAttempts,
    long initialBackoffMillis,
    long maxBackoffMillis,
    long maxRetryAfterMillis,
    long maxTotalWaitMillis
) {}
