package com.pathaopay.miniwallet.model;

import java.util.UUID;

/**
 * Stored outcome of an idempotent request: either the id of the resource it created, or the
 * error it failed with. {@code requestHash} detects reuse of the key with a different request.
 */
public record IdempotencyRecord(
        String key,
        String operation,
        String requestHash,
        UUID resourceId,
        String errorCode,
        String errorMessage
) {}
