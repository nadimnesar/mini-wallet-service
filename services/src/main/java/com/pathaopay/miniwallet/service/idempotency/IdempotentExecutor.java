package com.pathaopay.miniwallet.service.idempotency;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;
import com.pathaopay.miniwallet.common.exception.idempotency.IdempotencyKeyReusedException;
import com.pathaopay.miniwallet.common.exception.idempotency.ReplayedFailureException;
import com.pathaopay.miniwallet.model.IdempotencyRecord;
import com.pathaopay.miniwallet.repository.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Idempotency-Key engine shared by add-money and transfers: claims the key, runs the work and stores its outcome in
 * ONE database transaction, so a concurrent request with the same key waits and then replays the stored outcome:
 * exactly one execution.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class IdempotentExecutor {

    private final IdempotencyRepository idempotencyRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * Runs {@code work} once per key. Deterministic failures (wallet not found, insufficient balance, ...) are stored and
     * replayed too; retryable ones roll the claim back so the client can retry with the same key. A key used before with
     * the same {@code requestHash} replays the stored outcome (the resource is rebuilt with {@code rebuild}); with a
     * different hash it is rejected.
     */
    public <T> T execute(String idempotencyKey, String operation, String requestHash,
                  Supplier<Created<T>> work, Function<UUID, T> rebuild) {
        Outcome<T> outcome = transactionTemplate.execute(status -> {
            if (!idempotencyRepository.tryClaim(idempotencyKey, operation, requestHash)) {
                return replay(idempotencyKey, requestHash, rebuild);
            }
            try {
                Created<T> created = work.get();
                idempotencyRepository.completeWithResource(idempotencyKey, created.resourceId());
                return Outcome.success(created.response());
            } catch (MiniWalletException e) {
                if (e.isRetryable()) {
                    log.warn("{} failed with a retryable error, claim rolled back: key={} code={}",
                            operation, idempotencyKey, e.getErrorCode());
                    throw e; // rolls back the claim
                }
                log.info("{} rejected, outcome stored for replay: key={} code={} message={}",
                        operation, idempotencyKey, e.getErrorCode(), e.getMessage());
                idempotencyRepository.completeWithError(idempotencyKey, e.getErrorCode(), e.getMessage());
                return Outcome.failure(e);
            }
        });
        return outcome.getOrThrow();
    }

    /** SHA-256 of the operation's identifying parts, so a key reused with a different request is detected. */
    public static String fingerprint(String... parts) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private <T> Outcome<T> replay(String idempotencyKey, String requestHash, Function<UUID, T> rebuild) {
        IdempotencyRecord record = idempotencyRepository.findByKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Claimed idempotency key vanished: " + idempotencyKey));
        if (!record.requestHash().equals(requestHash)) {
            log.warn("Idempotency-Key reused with a different request: key={} operation={}",
                    idempotencyKey, record.operation());
            throw new IdempotencyKeyReusedException(ErrorMessage.IDEMPOTENCY_KEY_REUSED);
        }
        log.info("Replaying stored {} outcome: key={} resourceId={} errorCode={}",
                record.operation(), idempotencyKey, record.resourceId(), record.errorCode());
        if (record.resourceId() != null) {
            return Outcome.success(rebuild.apply(record.resourceId()));
        }
        return Outcome.failure(new ReplayedFailureException(record.errorCode(), record.errorMessage()));
    }

    /** The resource a successful operation created (stored for replays) and the response built from it. */
    public record Created<T>(UUID resourceId, T response) {
    }

    /** Result of an idempotent operation, returned from the transaction so failures can be committed. */
    private record Outcome<T>(T response, MiniWalletException failure) {
        static <T> Outcome<T> success(T response) {
            return new Outcome<>(response, null);
        }

        static <T> Outcome<T> failure(MiniWalletException failure) {
            return new Outcome<>(null, failure);
        }

        T getOrThrow() {
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
