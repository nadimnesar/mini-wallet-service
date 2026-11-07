package com.pathaopay.miniwallet.service.addmoney;

import com.pathaopay.miniwallet.client.AddMoneyQueueClient;
import com.pathaopay.miniwallet.client.PaymentGatewayClient;
import com.pathaopay.miniwallet.client.PaymentVerification;
import com.pathaopay.miniwallet.client.config.properties.AddMoneyMessagingProperties;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentDeclinedException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentMismatchException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentPendingException;
import com.pathaopay.miniwallet.common.util.LockTimeouts;
import com.pathaopay.miniwallet.common.util.UuidGenerator;
import com.pathaopay.miniwallet.model.AddMoneyRequestRecord;
import com.pathaopay.miniwallet.model.LedgerEntry;
import com.pathaopay.miniwallet.model.LedgerTransaction;
import com.pathaopay.miniwallet.model.enums.AddMoneyStatus;
import com.pathaopay.miniwallet.model.enums.EntryType;
import com.pathaopay.miniwallet.model.enums.TransactionType;
import com.pathaopay.miniwallet.repository.AddMoneyRequestRepository;
import com.pathaopay.miniwallet.repository.TransactionRepository;
import com.pathaopay.miniwallet.service.ledger.LedgerPoster;
import com.pathaopay.miniwallet.service.mapper.TransactionResponses;
import com.pathaopay.miniwallet.service.rules.TransactionRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.pathaopay.miniwallet.service.rules.TransactionRules.duplicateGatewayRef;

/**
 * The worker side of add-money: verifies the payment of an accepted request with the card gateway and credits the
 * wallet, or records why it could not, retrying transient failures.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AddMoneyProcessor {

    private final AddMoneyRequestRepository addMoneyRequestRepository;
    private final TransactionRepository transactionRepository;
    private final PaymentGatewayClient paymentGatewayClient;
    private final AddMoneyQueueClient addMoneyQueueClient;
    private final AddMoneyMessagingProperties messagingProperties;
    private final TransactionTemplate transactionTemplate;
    private final TransactionRules rules;
    private final LedgerPoster ledgerPoster;
    private final TransactionResponses responses;

    /**
     * Verifies the payment and credits the wallet for one accepted request, in two steps:
     * <ol>
     *   <li>the read-only checks and the gateway call run with <b>no transaction open</b>, so no connection and no row
     *   lock is held while the gateway answers;</li>
     *   <li>a short transaction locks the request row (so a duplicate delivery waits, then sees a final status and
     *   skips), and only then writes: the credit and the new status, or the failure and its attempt count.</li>
     * </ol>
     *
     * <p>A deterministic failure (declined, mismatch, ...) makes the request FAILED. A retryable one is counted and
     * left PENDING, then the request is parked on the retry queue, or, when the attempts are used up, FAILED and
     * dead-lettered. An unexpected error (a bug, a database error) is counted the same way, so a request that always
     * crashes the worker ends FAILED instead of cycling forever. The broker is called after the database transaction
     * committed.
     *
     * <p>A message that comes back before the backoff of the last recorded attempt has elapsed (a redelivery after a
     * crash between the commit and the ack) is parked again without counting an attempt, so the backoff and the attempt
     * count survive at-least-once delivery.
     */
    public void process(UUID requestId) {
        Processing processing;
        try {
            processing = verifyThenSettle(requestId);
        } catch (RuntimeException e) {
            if (LockTimeouts.isLockTimeout(e)) {
                processing = parkAfterLockTimeout(requestId, e);
            } else {
                log.error("Unexpected error while processing add-money request: requestId={}", requestId, e);
                processing = transactionTemplate.execute(status -> settleUnexpectedFailure(requestId));
            }
        }
        if (processing.retryAfterAttempt() > 0) {
            addMoneyQueueClient.scheduleRetry(requestId, processing.retryAfterAttempt());
        } else if (processing.deadLetterReason() != null) {
            addMoneyQueueClient.deadLetter(requestId, processing.deadLetterReason());
        }
    }

    /**
     * A row lock was not granted within the database lock_timeout: pure contention on the hot rows, not a fault of the
     * request. The transaction rolled back, so it is parked again on the retry queue WITHOUT counting an attempt;
     * counting it would let contention alone fail a request the customer has already paid.
     */
    private Processing parkAfterLockTimeout(UUID requestId, RuntimeException e) {
        log.warn("Add-money request hit the lock timeout, parked again without counting an attempt: requestId={}: {}",
                requestId, e.getMessage());
        int attempts = addMoneyRequestRepository.findById(requestId).map(AddMoneyRequestRecord::attempts).orElse(0);
        return Processing.retry(Math.max(1, attempts));
    }

    private Processing verifyThenSettle(UUID requestId) {
        AddMoneyRequestRecord request = addMoneyRequestRepository.findById(requestId).orElse(null);
        if (request == null) {
            log.warn("Add-money message for an unknown request, dropped: requestId={}", requestId);
            return Processing.none();
        }
        if (request.status() != AddMoneyStatus.PENDING) {
            log.info("Add-money request already {}, skipped: requestId={}", request.status(), requestId);
            return Processing.none();
        }
        if (isBeforeBackoffEnds(request)) {
            log.info("Add-money message came back before its backoff ended, parked again: requestId={} attempts={}",
                    requestId, request.attempts());
            return Processing.retry(request.attempts());
        }
        Verification verification = verify(request);
        return transactionTemplate.execute(status -> settle(requestId, verification));
    }

    /**
     * Everything that needs the gateway or only reads: the wallet must be a customer wallet, the gateway_ref must not be
     * credited yet and the gateway must confirm exactly this payment. Runs outside any transaction; a business or
     * gateway failure is returned (not thrown) so that {@link #settle} records it under the row lock.
     */
    private Verification verify(AddMoneyRequestRecord request) {
        try {
            String phone = responses.phoneOf(request.walletId());
            rules.requireCustomerWallet(phone);
            // Cheap early exit; the unique gateway_ref is the real guard (see insertAddMoney in creditWallet).
            if (transactionRepository.existsByGatewayRef(request.gatewayRef())) {
                throw duplicateGatewayRef(request.gatewayRef());
            }
            PaymentVerification payment = paymentGatewayClient.verify(
                    request.paymentMethod(), request.gatewayRef(), phone, request.amount());
            ensureCapturedAsRequested(payment, request, phone);
            return Verification.confirmed();
        } catch (MiniWalletException e) {
            return Verification.failed(e);
        }
    }

    /** Writes the outcome of {@link #verify} under the request row's lock. Runs in the short database transaction. */
    private Processing settle(UUID requestId, Verification verification) {
        AddMoneyRequestRecord request = addMoneyRequestRepository.lockById(requestId).orElse(null);
        if (request == null || request.status() != AddMoneyStatus.PENDING) {
            log.info("Add-money request finished by another delivery, skipped: requestId={}", requestId);
            return Processing.none();
        }
        if (isBeforeBackoffEnds(request)) {
            log.info("Add-money request was retried by another delivery, parked again: requestId={} attempts={}",
                    requestId, request.attempts());
            return Processing.retry(request.attempts());
        }
        int attempt = request.attempts() + 1;
        try {
            if (verification.failure() != null) {
                throw verification.failure();
            }
            Credited credited = creditWallet(request);
            addMoneyRequestRepository.markCompleted(requestId, credited.transaction().id(), attempt);
            log.info("Added money: requestId={} transactionId={} amount={} balance={} gatewayRef={} attempt={}",
                    requestId, credited.transaction().id(), request.amount(), credited.credit().balanceAfter(),
                    request.gatewayRef(), attempt);
            return Processing.none();
        } catch (MiniWalletException e) {
            return recordFailure(requestId, attempt, e.getErrorCode(), e.getMessage(), e.isRetryable());
        }
    }

    /** Counts an unexpected error as a failed attempt, in a fresh transaction (the failed one rolled back). */
    private Processing settleUnexpectedFailure(UUID requestId) {
        AddMoneyRequestRecord request = addMoneyRequestRepository.lockById(requestId).orElse(null);
        if (request == null || request.status() != AddMoneyStatus.PENDING) {
            return Processing.none();
        }
        if (isBeforeBackoffEnds(request)) {
            return Processing.retry(request.attempts());
        }
        return recordFailure(requestId, request.attempts() + 1, ErrorCode.INTERNAL_SERVER_ERROR,
                ErrorMessage.UNEXPECTED_ERROR, true);
    }

    /** Stores a failed attempt: final for a business rejection, otherwise a retry until the attempts run out. */
    private Processing recordFailure(UUID requestId, int attempt, String errorCode, String message, boolean retryable) {
        int maxAttempts = messagingProperties.maxAttempts();
        if (!retryable) {
            log.info("Add-money request rejected: requestId={} code={} message={} attempt={}",
                    requestId, errorCode, message, attempt);
            addMoneyRequestRepository.markFailed(requestId, attempt, errorCode, message);
            return Processing.none();
        }
        if (attempt < maxAttempts) {
            log.warn("Add-money attempt failed, will retry: requestId={} code={} attempt={}/{}",
                    requestId, errorCode, attempt, maxAttempts);
            addMoneyRequestRepository.recordRetry(requestId, attempt, errorCode, message);
            return Processing.retry(attempt);
        }
        String finalMessage = ErrorMessage.RETRIES_EXHAUSTED.formatted(message, attempt);
        log.error("Add-money request gave up: requestId={} code={} attempts={}", requestId, errorCode, attempt);
        addMoneyRequestRepository.markFailed(requestId, attempt, errorCode, finalMessage);
        return Processing.exhausted(errorCode + ": " + finalMessage);
    }

    /**
     * Whether the request was retried so recently that its last backoff has not elapsed yet. A message the broker
     * released from the retry queue is never early (it waited at least the delay after the attempt was recorded), so
     * an early one is a redelivery. Compares the application clock with the database timestamp, which is accurate to
     * far less than the delays; a skew only postpones such a request by one more delay.
     */
    private boolean isBeforeBackoffEnds(AddMoneyRequestRecord request) {
        int attempts = request.attempts();
        if (attempts == 0) {
            return false;
        }
        List<Duration> delays = messagingProperties.retryDelays();
        Duration delay = delays.get(Math.min(attempts, delays.size()) - 1);
        return Instant.now().isBefore(request.updatedAt().plus(delay));
    }

    /**
     * Records the add-money transaction: DEBIT the gateway clearing asset account and CREDIT the customer's liability
     * wallet, both for the captured amount, once the card gateway confirmed the payment.
     *
     * <p>The amount limits are not checked again here: they were enforced when the request was accepted, and the
     * customer has paid since. Re-reading the limits table would fail a paid request if a limit changed while it was
     * pending.
     */
    private Credited creditWallet(AddMoneyRequestRecord request) {
        UUID walletId = request.walletId();
        UUID clearingId = AppConstants.GATEWAY_CLEARING_WALLET_ID;
        Map<UUID, LedgerPoster.Chain> chains = ledgerPoster.lockAndLoadChains(Set.of(walletId, clearingId));

        LedgerTransaction transaction = transactionRepository.insertAddMoney(new LedgerTransaction(
                        UuidGenerator.v7(), TransactionType.ADD_MONEY, request.amount(), request.gatewayRef(),
                        request.paymentMethod(), null, null))
                .orElseThrow(() -> duplicateGatewayRef(request.gatewayRef()));
        LedgerEntry credit = chains.get(walletId).append(EntryType.ADD_MONEY, request.amount(), clearingId,
                transaction.id());
        chains.get(clearingId).append(EntryType.GATEWAY_CLEARING, request.amount(), walletId, transaction.id());
        return new Credited(transaction, credit);
    }

    private static void ensureCapturedAsRequested(PaymentVerification payment, AddMoneyRequestRecord request,
                                                  String phone) {
        if (payment.status() == PaymentVerification.Status.PENDING) {
            throw new PaymentPendingException(ErrorMessage.PAYMENT_PENDING.formatted(
                    request.paymentMethod(), request.gatewayRef(), payment.declineReason()));
        }
        if (payment.status() != PaymentVerification.Status.CAPTURED) {
            throw new PaymentDeclinedException(ErrorMessage.PAYMENT_DECLINED.formatted(
                    request.paymentMethod(), request.gatewayRef(), payment.status(), payment.declineReason()));
        }
        String requested = describe(request.amount(), AppConstants.CURRENCY_CODE, request.paymentMethod().name(), phone);
        String confirmed = describe(payment.amount(), payment.currency(), String.valueOf(payment.cardNetwork()),
                payment.merchantReference());
        if (!requested.equals(confirmed) || !request.gatewayRef().equals(payment.gatewayRef())) {
            throw new PaymentMismatchException(ErrorMessage.PAYMENT_MISMATCH.formatted(
                    request.gatewayRef(), requested, confirmed));
        }
    }

    private static String describe(long amount, String currency, String network, String merchantReference) {
        return amount + " " + currency + " via " + network + " for wallet " + merchantReference;
    }

    private record Credited(LedgerTransaction transaction, LedgerEntry credit) {
    }

    /** The outcome of the gateway step: confirmed (no failure) or the business or gateway failure to record. */
    private record Verification(MiniWalletException failure) {
        static Verification confirmed() {
            return new Verification(null);
        }

        static Verification failed(MiniWalletException failure) {
            return new Verification(failure);
        }
    }

    /** What to do with the broker once the database transaction of {@link #process} is over. */
    private record Processing(int retryAfterAttempt, String deadLetterReason) {
        /** Nothing for the broker: the request finished (credited or rejected) or was not ours to process. */
        static Processing none() {
            return new Processing(0, null);
        }

        static Processing retry(int failedAttempts) {
            return new Processing(failedAttempts, null);
        }

        static Processing exhausted(String reason) {
            return new Processing(0, reason);
        }
    }
}
