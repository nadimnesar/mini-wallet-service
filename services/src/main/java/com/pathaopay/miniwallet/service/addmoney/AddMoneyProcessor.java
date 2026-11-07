package com.pathaopay.miniwallet.service.addmoney;

import com.pathaopay.miniwallet.client.AddMoneyQueueClient;
import com.pathaopay.miniwallet.client.PaymentGatewayClient;
import com.pathaopay.miniwallet.client.PaymentVerification;
import com.pathaopay.miniwallet.client.messaging.AddMoneyMessagingProperties;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentDeclinedException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentMismatchException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentPendingException;
import com.pathaopay.miniwallet.common.util.UuidGenerator;
import com.pathaopay.miniwallet.model.AddMoneyRequestRecord;
import com.pathaopay.miniwallet.model.LedgerEntry;
import com.pathaopay.miniwallet.model.LedgerTransaction;
import com.pathaopay.miniwallet.model.Wallet;
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
     * Verifies the payment and credits the wallet for one accepted request, in one database transaction that holds the
     * request row's lock (so a duplicate delivery waits, then sees a final status and skips). Trade-off: the
     * transaction stays open during the gateway call (bounded by the gateway read timeout), holding a connection and
     * that row lock, but no wallet lock.
     *
     * <p>A deterministic failure (declined, mismatch, ...) makes the request FAILED. A retryable one is counted and
     * left PENDING, then the request is parked on the retry queue, or, when the attempts are used up, FAILED and
     * dead-lettered. The broker is called after the database transaction committed.
     */
    public void process(UUID requestId) {
        int maxAttempts = messagingProperties.maxAttempts();
        Processing processing = transactionTemplate.execute(status -> {
            AddMoneyRequestRecord request = addMoneyRequestRepository.lockById(requestId).orElse(null);
            if (request == null) {
                log.warn("Add-money message for an unknown request, dropped: requestId={}", requestId);
                return Processing.none();
            }
            if (request.status() != AddMoneyStatus.PENDING) {
                log.info("Add-money request already {}, skipped: requestId={}", request.status(), requestId);
                return Processing.none();
            }
            int attempt = request.attempts() + 1;
            try {
                Credited credited = creditWallet(request);
                addMoneyRequestRepository.markCompleted(requestId, credited.transaction().id(), attempt);
                log.info("Added money: requestId={} transactionId={} amount={} balance={} gatewayRef={} attempt={}",
                        requestId, credited.transaction().id(), request.amount(), credited.credit().balanceAfter(),
                        request.gatewayRef(), attempt);
                return Processing.none();
            } catch (MiniWalletException e) {
                if (!e.isRetryable()) {
                    log.info("Add-money request rejected: requestId={} code={} message={} attempt={}",
                            requestId, e.getErrorCode(), e.getMessage(), attempt);
                    addMoneyRequestRepository.markFailed(requestId, attempt, e.getErrorCode(), e.getMessage());
                    return Processing.none();
                }
                if (attempt < maxAttempts) {
                    log.warn("Add-money attempt failed, will retry: requestId={} code={} attempt={}/{}",
                            requestId, e.getErrorCode(), attempt, maxAttempts);
                    addMoneyRequestRepository.recordRetry(requestId, attempt, e.getErrorCode(), e.getMessage());
                    return Processing.retry(attempt);
                }
                String message = ErrorMessage.RETRIES_EXHAUSTED.formatted(e.getMessage(), attempt);
                log.error("Add-money request gave up: requestId={} code={} attempts={}", requestId, e.getErrorCode(), attempt);
                addMoneyRequestRepository.markFailed(requestId, attempt, e.getErrorCode(), message);
                return Processing.exhausted(e.getErrorCode() + ": " + message);
            }
        });
        if (processing.retryAfterAttempt() > 0) {
            addMoneyQueueClient.scheduleRetry(requestId, processing.retryAfterAttempt());
        } else if (processing.deadLetterReason() != null) {
            addMoneyQueueClient.deadLetter(requestId, processing.deadLetterReason());
        }
    }

    /**
     * Records the add-money transaction: DEBIT the gateway clearing asset account and CREDIT the customer's liability
     * wallet, both for the captured amount, once the card gateway confirms the payment.
     *
     * <p>The amount limits are not checked again here: they were enforced when the request was accepted, and the
     * customer has paid since. Re-reading the limits table would fail a paid request if a limit changed while it was
     * pending.
     */
    private Credited creditWallet(AddMoneyRequestRecord request) {
        String phone = responses.phoneOf(request.walletId());
        Wallet wallet = rules.requireCustomerWallet(phone);
        UUID walletId = wallet.id();
        // Cheap early exit; the unique gateway_ref is the real guard (see insertAddMoney below).
        if (transactionRepository.existsByGatewayRef(request.gatewayRef())) {
            throw duplicateGatewayRef(request.gatewayRef());
        }

        PaymentVerification payment = paymentGatewayClient.verify(
                request.paymentMethod(), request.gatewayRef(), phone, request.amount());
        ensureCapturedAsRequested(payment, request, phone);

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
