package com.pathaopay.miniwallet.service.impl;

import com.pathaopay.miniwallet.client.PaymentGatewayClient;
import com.pathaopay.miniwallet.client.PaymentVerification;
import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorMessages;
import com.pathaopay.miniwallet.common.constant.AppConstants.Money;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;
import com.pathaopay.miniwallet.common.exception.idempotency.IdempotencyKeyReusedException;
import com.pathaopay.miniwallet.common.exception.idempotency.ReplayedFailureException;
import com.pathaopay.miniwallet.common.exception.payment.DuplicateGatewayRefException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentDeclinedException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentMismatchException;
import com.pathaopay.miniwallet.common.exception.wallet.WalletNotFoundException;
import com.pathaopay.miniwallet.common.util.UuidGenerator;
import com.pathaopay.miniwallet.dto.*;
import com.pathaopay.miniwallet.model.IdempotencyRecord;
import com.pathaopay.miniwallet.model.TransactionType;
import com.pathaopay.miniwallet.model.WalletTransaction;
import com.pathaopay.miniwallet.repository.IdempotencyRepository;
import com.pathaopay.miniwallet.repository.TransactionRepository;
import com.pathaopay.miniwallet.repository.WalletRepository;
import com.pathaopay.miniwallet.service.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class DefaultTransactionService implements TransactionService {

    private static final String ADD_MONEY_OPERATION = "ADD_MONEY";

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final PaymentGatewayClient paymentGatewayClient;
    private final TransactionTemplate transactionTemplate;

    public DefaultTransactionService(WalletRepository walletRepository,
                                     TransactionRepository transactionRepository,
                                     IdempotencyRepository idempotencyRepository,
                                     PaymentGatewayClient paymentGatewayClient,
                                     TransactionTemplate transactionTemplate) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.paymentGatewayClient = paymentGatewayClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Credits the wallet once the card network's gateway confirms the payment.
     *
     * <p>Claiming the Idempotency-Key, crediting the ledger and storing the outcome happen in one
     * database transaction, so a concurrent request with the same key waits and then replays the
     * stored outcome: exactly one execution. Deterministic failures (wallet not found, payment
     * declined, ...) are stored and replayed too; transient gateway failures roll the claim back so
     * the client can retry with the same key.
     *
     * <p>Trade-off: the transaction stays open during the gateway call (bounded by the gateway
     * read timeout), holding a connection and the key's row lock, but no wallet lock.
     */
    @Override
    public AddMoneyResponse addMoney(AddMoneyRequest request, String idempotencyKey) {
        UUID walletId = request.walletId();
        String requestHash = sha256Hex(String.join("\n", ADD_MONEY_OPERATION, walletId.toString(),
                request.amount().toString(), request.gatewayRef().toString(), request.paymentMethod().name()));

        Outcome outcome = transactionTemplate.execute(status -> {
            if (!idempotencyRepository.tryClaim(idempotencyKey, ADD_MONEY_OPERATION, requestHash)) {
                return replay(idempotencyKey, requestHash);
            }
            try {
                WalletTransaction entry = creditWallet(walletId, request);
                idempotencyRepository.completeWithResource(idempotencyKey, entry.id());
                return Outcome.success(toAddMoneyResponse(entry));
            } catch (MiniWalletException e) {
                if (e.isRetryable()) {
                    throw e; // rolls back the claim
                }
                idempotencyRepository.completeWithError(idempotencyKey, e.getErrorCode(), e.getMessage());
                return Outcome.failure(e);
            }
        });
        return outcome.getOrThrow();
    }

    private WalletTransaction creditWallet(UUID walletId, AddMoneyRequest request) {
        if (!walletRepository.existsById(walletId)) {
            throw new WalletNotFoundException(walletId);
        }
        // Cheap early exit; the unique index is the real guard (see appendAddMoney below).
        if (transactionRepository.existsAddMoneyWithGatewayRef(request.gatewayRef())) {
            throw duplicateGatewayRef(request.gatewayRef());
        }

        PaymentVerification payment = paymentGatewayClient.verify(
                request.paymentMethod(), request.gatewayRef(), request.amount());
        ensureCapturedAsRequested(payment, request);

        // Serialize appends to this wallet, then continue its ledger chain.
        walletRepository.lockForUpdate(walletId);
        WalletTransaction latest = transactionRepository.findLatestEntry(walletId).orElse(null);
        long entryNo = latest == null ? 1 : latest.entryNo() + 1;
        long balanceBefore = latest == null ? 0 : latest.balanceAfter();

        WalletTransaction entry = new WalletTransaction(UuidGenerator.v7(), walletId, entryNo,
                TransactionType.ADD_MONEY, request.amount(), balanceBefore, balanceBefore + request.amount(),
                null, request.gatewayRef(), request.paymentMethod(), null);
        return transactionRepository.appendAddMoney(entry)
                .orElseThrow(() -> duplicateGatewayRef(request.gatewayRef()));
    }

    private static void ensureCapturedAsRequested(PaymentVerification payment, AddMoneyRequest request) {
        if (payment.status() != PaymentVerification.Status.CAPTURED) {
            throw new PaymentDeclinedException(ErrorMessages.PAYMENT_DECLINED.formatted(
                    request.paymentMethod(), request.gatewayRef(), payment.status(), payment.declineReason()));
        }
        String requested = describe(request.amount(), Money.CURRENCY_CODE, request.paymentMethod().name());
        String confirmed = describe(payment.amount(), payment.currency(), String.valueOf(payment.cardNetwork()));
        if (!requested.equals(confirmed) || !request.gatewayRef().equals(payment.gatewayRef())) {
            throw new PaymentMismatchException(ErrorMessages.PAYMENT_MISMATCH.formatted(
                    request.gatewayRef(), requested, confirmed));
        }
    }

    private static String describe(long amount, String currency, String network) {
        return amount + " " + currency + " via " + network;
    }

    private Outcome replay(String idempotencyKey, String requestHash) {
        IdempotencyRecord record = idempotencyRepository.findByKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Claimed idempotency key vanished: " + idempotencyKey));
        if (!record.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(ErrorMessages.IDEMPOTENCY_KEY_REUSED);
        }
        if (record.resourceId() != null) {
            WalletTransaction entry = transactionRepository.findById(record.resourceId())
                    .orElseThrow(() -> new IllegalStateException("Missing ledger entry " + record.resourceId()));
            return Outcome.success(toAddMoneyResponse(entry));
        }
        return Outcome.failure(new ReplayedFailureException(record.errorCode(), record.errorMessage()));
    }

    private static DuplicateGatewayRefException duplicateGatewayRef(UUID gatewayRef) {
        return new DuplicateGatewayRefException(ErrorMessages.DUPLICATE_GATEWAY_REF_PREFIX + gatewayRef);
    }

    private static AddMoneyResponse toAddMoneyResponse(WalletTransaction entry) {
        return new AddMoneyResponse(entry.id(), entry.walletId(), entry.amount(), entry.balanceAfter(),
                entry.referenceId(), entry.paymentMethod(), entry.createdAt());
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Result of an idempotent operation, returned from the transaction so failures can be committed. */
    private record Outcome(AddMoneyResponse response, MiniWalletException failure) {
        static Outcome success(AddMoneyResponse response) {
            return new Outcome(response, null);
        }

        static Outcome failure(MiniWalletException failure) {
            return new Outcome(null, failure);
        }

        AddMoneyResponse getOrThrow() {
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    @Override
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        return null;
    }

    @Override
    public TransactionPageResponse getTransactions(
            String walletId,
            String type,
            Instant from,
            Instant to,
            int page,
            int pageSize
    ) {
        return null;
    }

    @Override
    public TransactionDetailResponse getTransactionDetails(String transactionId) {
        return null;
    }
}
