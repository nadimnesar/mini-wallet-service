package com.pathaopay.miniwallet.service.impl;

import com.pathaopay.miniwallet.client.PaymentGatewayClient;
import com.pathaopay.miniwallet.client.PaymentVerification;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;
import com.pathaopay.miniwallet.common.exception.idempotency.IdempotencyKeyReusedException;
import com.pathaopay.miniwallet.common.exception.idempotency.ReplayedFailureException;
import com.pathaopay.miniwallet.common.exception.payment.DuplicateGatewayRefException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentDeclinedException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentMismatchException;
import com.pathaopay.miniwallet.common.exception.transaction.InsufficientBalanceException;
import com.pathaopay.miniwallet.common.exception.transaction.InvalidDateRangeException;
import com.pathaopay.miniwallet.common.exception.transaction.SameWalletTransferException;
import com.pathaopay.miniwallet.common.exception.transaction.TransactionNotFoundException;
import com.pathaopay.miniwallet.common.exception.wallet.WalletNotFoundException;
import com.pathaopay.miniwallet.common.util.UuidGenerator;
import com.pathaopay.miniwallet.dto.*;
import com.pathaopay.miniwallet.model.IdempotencyRecord;
import com.pathaopay.miniwallet.model.TransactionFilter;
import com.pathaopay.miniwallet.model.TransactionType;
import com.pathaopay.miniwallet.model.WalletTransaction;
import com.pathaopay.miniwallet.repository.IdempotencyRepository;
import com.pathaopay.miniwallet.repository.TransactionRepository;
import com.pathaopay.miniwallet.repository.WalletRepository;
import com.pathaopay.miniwallet.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class DefaultTransactionService implements TransactionService {

    private static final String ADD_MONEY_OPERATION = "ADD_MONEY";
    private static final String TRANSFER_OPERATION = "TRANSFER";
    private static final String TRANSFER_STATUS_COMPLETED = "COMPLETED";

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final PaymentGatewayClient paymentGatewayClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * Credits the wallet once the card network's gateway confirms the payment.
     *
     * <p>Trade-off: the transaction stays open during the gateway call (bounded by the gateway
     * read timeout), holding a connection and the key's row lock, but no wallet lock.
     *
     * @see #executeIdempotently
     */
    @Override
    public AddMoneyResponse addMoney(AddMoneyRequest request, String idempotencyKey) {
        UUID walletId = request.walletId();
        String requestHash = sha256Hex(String.join("\n", ADD_MONEY_OPERATION, walletId.toString(),
                request.amount().toString(), request.gatewayRef().toString(), request.paymentMethod().name()));

        return executeIdempotently(idempotencyKey, ADD_MONEY_OPERATION, requestHash, () -> {
            AddMoneyResponse response = toAddMoneyResponse(creditWallet(walletId, request));
            return new Created<>(response.transactionId(), response);
        }, this::rebuildAddMoney);
    }

    /**
     * Moves money between two wallets: both ledger legs (TRANSFER_OUT and TRANSFER_IN, sharing the
     * transfer id as reference_id) are appended in one database transaction, so either both exist or
     * neither does. Wallet rows are locked in ascending id order, so opposite transfers between the
     * same two wallets cannot deadlock.
     *
     * @see #executeIdempotently
     */
    @Override
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        UUID from = request.fromWalletId();
        UUID to = request.toWalletId();
        String requestHash = sha256Hex(String.join("\n", TRANSFER_OPERATION, from.toString(), to.toString(),
                request.amount().toString(), request.note() == null ? "" : request.note()));

        return executeIdempotently(idempotencyKey, TRANSFER_OPERATION, requestHash, () -> {
            TransferResponse response = moveMoney(from, to, request.amount());
            return new Created<>(response.transferId(), response);
        }, this::rebuildTransfer);
    }

    /**
     * Claims the Idempotency-Key, runs {@code work} and stores its outcome in ONE database
     * transaction, so a concurrent request with the same key waits and then replays the stored
     * outcome: exactly one execution. Deterministic failures (wallet not found, insufficient
     * balance, payment declined, ...) are stored and replayed too; retryable ones roll the claim
     * back so the client can retry with the same key.
     */
    private <T> T executeIdempotently(String idempotencyKey, String operation, String requestHash,
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

    private TransferResponse moveMoney(UUID from, UUID to, long amount) {
        if (from.equals(to)) {
            throw new SameWalletTransferException(ErrorMessage.SAME_WALLET_TRANSFER);
        }
        // Ascending id order, the same for every transfer, so two transfers can never wait on each other.
        boolean fromExists = false;
        boolean toExists = false;
        for (UUID id : from.compareTo(to) < 0 ? List.of(from, to) : List.of(to, from)) {
            boolean locked = walletRepository.lockForUpdate(id);
            if (id.equals(from)) {
                fromExists = locked;
            } else {
                toExists = locked;
            }
        }
        if (!fromExists) {
            throw new WalletNotFoundException(from);
        }
        if (!toExists) {
            throw new WalletNotFoundException(to);
        }

        WalletTransaction fromLatest = transactionRepository.findLatestEntry(from).orElse(null);
        WalletTransaction toLatest = transactionRepository.findLatestEntry(to).orElse(null);
        long fromBalance = fromLatest == null ? 0 : fromLatest.balanceAfter();
        long toBalance = toLatest == null ? 0 : toLatest.balanceAfter();
        if (fromBalance < amount) {
            throw new InsufficientBalanceException(ErrorMessage.INSUFFICIENT_BALANCE.formatted(from));
        }

        UUID transferId = UuidGenerator.v7();
        WalletTransaction out = transactionRepository.appendTransferLeg(new WalletTransaction(
                UuidGenerator.v7(), from, fromLatest == null ? 1 : fromLatest.entryNo() + 1,
                TransactionType.TRANSFER_OUT, amount, fromBalance, fromBalance - amount, to, transferId, null, null));
        transactionRepository.appendTransferLeg(new WalletTransaction(
                UuidGenerator.v7(), to, toLatest == null ? 1 : toLatest.entryNo() + 1,
                TransactionType.TRANSFER_IN, amount, toBalance, toBalance + amount, from, transferId, null, null));
        return toTransferResponse(out);
    }

    private static void ensureCapturedAsRequested(PaymentVerification payment, AddMoneyRequest request) {
        if (payment.status() != PaymentVerification.Status.CAPTURED) {
            throw new PaymentDeclinedException(ErrorMessage.PAYMENT_DECLINED.formatted(
                    request.paymentMethod(), request.gatewayRef(), payment.status(), payment.declineReason()));
        }
        String requested = describe(request.amount(), AppConstants.CURRENCY_CODE, request.paymentMethod().name());
        String confirmed = describe(payment.amount(), payment.currency(), String.valueOf(payment.cardNetwork()));
        if (!requested.equals(confirmed) || !request.gatewayRef().equals(payment.gatewayRef())) {
            throw new PaymentMismatchException(ErrorMessage.PAYMENT_MISMATCH.formatted(
                    request.gatewayRef(), requested, confirmed));
        }
    }

    private static String describe(long amount, String currency, String network) {
        return amount + " " + currency + " via " + network;
    }

    private <T> Outcome<T> replay(String idempotencyKey, String requestHash, Function<UUID, T> rebuild) {
        IdempotencyRecord record = idempotencyRepository.findByKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Claimed idempotency key vanished: " + idempotencyKey));
        if (!record.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(ErrorMessage.IDEMPOTENCY_KEY_REUSED);
        }
        if (record.resourceId() != null) {
            return Outcome.success(rebuild.apply(record.resourceId()));
        }
        return Outcome.failure(new ReplayedFailureException(record.errorCode(), record.errorMessage()));
    }

    private AddMoneyResponse rebuildAddMoney(UUID transactionId) {
        return toAddMoneyResponse(transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException("Missing ledger entry " + transactionId)));
    }

    private TransferResponse rebuildTransfer(UUID transferId) {
        return toTransferResponse(transactionRepository.findTransferOut(transferId)
                .orElseThrow(() -> new IllegalStateException("Missing transfer " + transferId)));
    }

    private static DuplicateGatewayRefException duplicateGatewayRef(UUID gatewayRef) {
        return new DuplicateGatewayRefException(ErrorMessage.DUPLICATE_GATEWAY_REF_PREFIX + gatewayRef);
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

    /** The resource a successful operation created (stored for replays) and the response built from it. */
    private record Created<T>(UUID resourceId, T response) {
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

    private static TransferResponse toTransferResponse(WalletTransaction out) {
        return new TransferResponse(out.referenceId(), out.walletId(), out.counterpartyWalletId(), out.amount(),
                TRANSFER_STATUS_COMPLETED, out.createdAt());
    }

    /** Served by the read replica; entries may lag the primary by the replication delay. */
    @Override
    @Transactional(readOnly = true)
    public TransactionPageResponse getTransactions(
            UUID walletId,
            TransactionType type,
            Instant from,
            Instant to,
            int page,
            int pageSize
    ) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidDateRangeException();
        }
        if (walletId != null && !walletRepository.existsById(walletId)) {
            throw new WalletNotFoundException(walletId);
        }
        TransactionFilter filter = new TransactionFilter(walletId, type, from, to);
        long totalItems = transactionRepository.count(filter);
        List<TransactionItemResponse> items = transactionRepository
                .search(filter, pageSize, (long) (page - 1) * pageSize).stream()
                .map(e -> new TransactionItemResponse(e.id(), e.type(), e.amount(), e.balanceAfter(),
                        e.counterpartyWalletId(), e.referenceId(), e.createdAt()))
                .toList();
        int totalPages = (int) ((totalItems + pageSize - 1) / pageSize);
        return new TransactionPageResponse(items, page, pageSize, totalItems, totalPages);
    }

    /** Served by the read replica; entries may lag the primary by the replication delay. */
    @Override
    @Transactional(readOnly = true)
    public TransactionDetailResponse getTransactionDetails(UUID transactionId) {
        WalletTransaction e = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId.toString()));
        return new TransactionDetailResponse(e.id(), e.walletId(), e.type(), e.amount(), e.balanceAfter(),
                e.counterpartyWalletId(), e.referenceId(), e.createdAt());
    }
}
