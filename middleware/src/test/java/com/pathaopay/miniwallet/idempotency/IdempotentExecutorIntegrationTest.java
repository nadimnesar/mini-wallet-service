package com.pathaopay.miniwallet.idempotency;

import com.pathaopay.miniwallet.common.constant.DbConstants.IdempotencyKeys;
import com.pathaopay.miniwallet.common.exception.idempotency.IdempotencyKeyReusedException;
import com.pathaopay.miniwallet.common.exception.idempotency.ReplayedFailureException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentGatewayException;
import com.pathaopay.miniwallet.common.exception.transaction.InvalidAmountException;
import com.pathaopay.miniwallet.service.idempotency.IdempotentExecutor;
import com.pathaopay.miniwallet.service.idempotency.IdempotentExecutor.Created;
import com.pathaopay.miniwallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The idempotency engine against the real idempotency_keys table. */
class IdempotentExecutorIntegrationTest extends AbstractIntegrationTest {

    private static final String HASH_1 = IdempotentExecutor.fingerprint("one");
    private static final String HASH_2 = IdempotentExecutor.fingerprint("two");

    @Autowired
    private IdempotentExecutor executor;

    private long storedKeys() {
        return jdbcClient.sql("SELECT count(*) FROM " + IdempotencyKeys.TABLE).query(Long.class).single();
    }

    @Test
    void aRetryableFailureRollsBackTheClaimSoTheKeyCanBeUsedAgain() {
        assertThrows(PaymentGatewayException.class, () -> executor.execute("k", "TRANSFER", HASH_1, () -> {
            throw new PaymentGatewayException("gateway down", new RuntimeException("boom"));
        }, id -> "replayed"));
        assertEquals(0, storedKeys());

        UUID resource = UUID.randomUUID();
        String result = executor.execute("k", "TRANSFER", HASH_1, () -> new Created<>(resource, "created"), id -> "replayed");

        assertEquals("created", result);
        assertEquals(1, storedKeys());
    }

    @Test
    void aSuccessIsReplayedFromTheStoredResourceWithoutRunningTheWorkAgain() {
        UUID resource = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        String first = executor.execute("k", "TRANSFER", HASH_1, () -> {
            runs.incrementAndGet();
            return new Created<>(resource, "created");
        }, id -> "replayed " + id);
        String second = executor.execute("k", "TRANSFER", HASH_1, () -> {
            runs.incrementAndGet();
            return new Created<>(resource, "again");
        }, id -> "replayed " + id);

        assertEquals("created", first);
        assertEquals("replayed " + resource, second);
        assertEquals(1, runs.get());
    }

    @Test
    void aDeterministicFailureIsStoredAndReRaisedOnEveryReplay() {
        AtomicInteger runs = new AtomicInteger();
        assertThrows(InvalidAmountException.class, () -> executor.execute("k", "TRANSFER", HASH_1, () -> {
            runs.incrementAndGet();
            throw new InvalidAmountException("too small");
        }, id -> "replayed"));

        ReplayedFailureException replay = assertThrows(ReplayedFailureException.class,
                () -> executor.execute("k", "TRANSFER", HASH_1, () -> {
                    runs.incrementAndGet();
                    return new Created<>(UUID.randomUUID(), "created");
                }, id -> "replayed"));

        assertEquals(new InvalidAmountException("too small").getErrorCode(), replay.getErrorCode());
        assertEquals("too small", replay.getMessage());
        assertEquals(1, runs.get());
    }

    @Test
    void theSameKeyWithAnotherRequestHashIsRejected() {
        executor.execute("k", "TRANSFER", HASH_1, () -> new Created<>(UUID.randomUUID(), "created"), id -> "replayed");

        assertThrows(IdempotencyKeyReusedException.class, () -> executor.execute("k", "TRANSFER", HASH_2,
                () -> new Created<>(UUID.randomUUID(), "created"), id -> "replayed"));
    }

    @Test
    void fingerprintIsStableAndDependsOnEveryPart() {
        assertEquals(64, IdempotentExecutor.fingerprint("a", "b").length());
        assertEquals(IdempotentExecutor.fingerprint("a", "b"), IdempotentExecutor.fingerprint("a", "b"));
        assertNotEquals(IdempotentExecutor.fingerprint("a", "b"), IdempotentExecutor.fingerprint("a", "c"));
        assertNotEquals(IdempotentExecutor.fingerprint("a", "b"), IdempotentExecutor.fingerprint("ab"));
    }
}
