package com.pathaopay.miniwallet.service.impl;

import com.pathaopay.miniwallet.client.AddMoneyQueueClient;
import com.pathaopay.miniwallet.client.PaymentGatewayClient;
import com.pathaopay.miniwallet.client.PaymentVerification;
import com.pathaopay.miniwallet.client.messaging.AddMoneyMessagingProperties;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;
import com.pathaopay.miniwallet.common.exception.idempotency.IdempotencyKeyReusedException;
import com.pathaopay.miniwallet.common.exception.idempotency.ReplayedFailureException;
import com.pathaopay.miniwallet.common.exception.payment.DuplicateGatewayRefException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentDeclinedException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentMismatchException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentPendingException;
import com.pathaopay.miniwallet.common.exception.transaction.AddMoneyRequestNotFoundException;
import com.pathaopay.miniwallet.common.exception.transaction.InsufficientBalanceException;
import com.pathaopay.miniwallet.common.exception.transaction.InvalidAmountException;
import com.pathaopay.miniwallet.common.exception.transaction.InvalidDateRangeException;
import com.pathaopay.miniwallet.common.exception.transaction.SameWalletTransferException;
import com.pathaopay.miniwallet.common.exception.transaction.SystemWalletException;
import com.pathaopay.miniwallet.common.exception.transaction.TransactionNotFoundException;
import com.pathaopay.miniwallet.common.exception.wallet.WalletNotFoundException;
import com.pathaopay.miniwallet.common.util.PhoneNumberUtils;
import com.pathaopay.miniwallet.common.util.UuidGenerator;
import com.pathaopay.miniwallet.dto.request.*;
import com.pathaopay.miniwallet.dto.response.*;
import com.pathaopay.miniwallet.model.AddMoneyRequestRecord;
import com.pathaopay.miniwallet.model.IdempotencyRecord;
import com.pathaopay.miniwallet.model.LedgerEntryView;
import com.pathaopay.miniwallet.model.LedgerTransaction;
import com.pathaopay.miniwallet.model.TransactionLimit;
import com.pathaopay.miniwallet.model.enums.AddMoneyStatus;
import com.pathaopay.miniwallet.model.enums.TransactionType;
import com.pathaopay.miniwallet.model.TransactionFilter;
import com.pathaopay.miniwallet.model.enums.EntryType;
import com.pathaopay.miniwallet.model.Wallet;
import com.pathaopay.miniwallet.model.LedgerEntry;
import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.repository.AddMoneyRequestRepository;
import com.pathaopay.miniwallet.repository.FeeRepository;
import com.pathaopay.miniwallet.repository.IdempotencyRepository;
import com.pathaopay.miniwallet.repository.LimitRepository;
import com.pathaopay.miniwallet.repository.TransactionRepository;
import com.pathaopay.miniwallet.repository.WalletRepository;
import com.pathaopay.miniwallet.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultTransactionService implements TransactionService {

    private static final String ADD_MONEY_OPERATION = "ADD_MONEY";
    private static final String TRANSFER_OPERATION = "TRANSFER";
    private static final String TRANSFER_STATUS_COMPLETED = "COMPLETED";

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final FeeRepository feeRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final LimitRepository limitRepository;
    private final AddMoneyRequestRepository addMoneyRequestRepository;
    private final PaymentGatewayClient paymentGatewayClient;
    private final AddMoneyQueueClient addMoneyQueueClient;
    private final AddMoneyMessagingProperties messagingProperties;
    private final TransactionTemplate transactionTemplate;

    /**
     * Accepts an add-money request: checks everything that needs no gateway (amount limits, a customer wallet, an
     * unused gateway_ref) and stores a PENDING request, all under the Idempotency-Key. The gateway call and the
     * credit happen later in {@link #processAddMoney}, so no connection is held while the gateway answers.
     *
     * @see #executeIdempotently
     */
    @Override
    public AddMoneyStatusResponse addMoney(AddMoneyRequest request, String idempotencyKey) {
        String phone = PhoneNumberUtils.normalize(request.phone());
        String requestHash = sha256Hex(String.join("\n", ADD_MONEY_OPERATION, phone,
                request.amount().toString(), request.gatewayRef().toString(), request.paymentMethod().name()));

        return executeIdempotently(idempotencyKey, ADD_MONEY_OPERATION, requestHash, () -> {
            requireWithinLimit(TransactionType.ADD_MONEY, request.amount(), 0);
            Wallet wallet = requireCustomerWallet(phone);
            // Cheap early exit; the unique indexes on gateway_ref are the real guard.
            if (transactionRepository.existsByGatewayRef(request.gatewayRef())) {
                throw duplicateGatewayRef(request.gatewayRef());
            }
            AddMoneyRequestRecord accepted = addMoneyRequestRepository.insert(new AddMoneyRequestRecord(
                            UuidGenerator.v7(), wallet.id(), request.amount(), request.gatewayRef(),
                            request.paymentMethod(), AddMoneyStatus.PENDING, 0, null, null, null, null, null))
                    .orElseThrow(() -> duplicateGatewayRef(request.gatewayRef()));
            log.info("Accepted add-money request: requestId={} phone={} amount={} gatewayRef={}",
                    accepted.id(), phone, accepted.amount(), accepted.gatewayRef());
            return new Created<>(accepted.id(), toAddMoneyStatus(accepted, phone));
        }, this::rebuildAddMoney);
    }

    @Override
    @Transactional(readOnly = true)
    public AddMoneyStatusResponse getAddMoneyRequest(UUID requestId) {
        return rebuildAddMoney(requestId);
    }

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
    @Override
    public void processAddMoney(UUID requestId) {
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

    /** What to do with the broker once the database transaction of {@link #processAddMoney} is over. */
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

    /**
     * Moves money between two customer wallets: one TRANSFER transaction (its id is the transfer id) and four ledger
     * entries (TRANSFER_OUT and FEE_OUT on the sender, TRANSFER_IN on the receiver, FEE_IN on the revenue account) are
     * written in one database transaction, so either all exist or none does. Wallet rows are locked in ascending id order, so
     * opposite transfers between the same two wallets cannot deadlock.
     *
     * @see #executeIdempotently
     */
    @Override
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        String from = PhoneNumberUtils.normalize(request.fromPhone());
        String to = PhoneNumberUtils.normalize(request.toPhone());
        String requestHash = sha256Hex(String.join("\n", TRANSFER_OPERATION, from, to,
                request.amount().toString(), request.note() == null ? "" : request.note()));

        return executeIdempotently(idempotencyKey, TRANSFER_OPERATION, requestHash, () -> {
            TransferResponse response = moveMoney(from, to, request.amount(), request.note());
            log.info("Transferred money: transferId={} from={} to={} amount={}",
                    response.transferId(), from, to, response.amount());
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

    /**
     * Records the add-money transaction: DEBIT the gateway clearing asset account and CREDIT the customer's liability
     * wallet, both for the captured amount, once the card gateway confirms the payment.
     */
    private Credited creditWallet(AddMoneyRequestRecord request) {
        requireWithinLimit(TransactionType.ADD_MONEY, request.amount(), 0);
        String phone = phoneOf(request.walletId());
        Wallet wallet = requireCustomerWallet(phone);
        UUID walletId = wallet.id();
        // Cheap early exit; the unique gateway_ref is the real guard (see insertAddMoney below).
        if (transactionRepository.existsByGatewayRef(request.gatewayRef())) {
            throw duplicateGatewayRef(request.gatewayRef());
        }

        PaymentVerification payment = paymentGatewayClient.verify(
                request.paymentMethod(), request.gatewayRef(), phone, request.amount());
        ensureCapturedAsRequested(payment, request, phone);

        UUID clearingId = AppConstants.GATEWAY_CLEARING_WALLET_ID;
        Map<UUID, Chain> chains = lockAndLoadChains(Set.of(walletId, clearingId));

        LedgerTransaction transaction = transactionRepository.insertAddMoney(new LedgerTransaction(
                        UuidGenerator.v7(), TransactionType.ADD_MONEY, request.amount(), request.gatewayRef(),
                        request.paymentMethod(), null, null))
                .orElseThrow(() -> duplicateGatewayRef(request.gatewayRef()));
        LedgerEntry credit = chains.get(walletId).append(transactionRepository, EntryType.ADD_MONEY,
                request.amount(), clearingId, transaction.id());
        chains.get(clearingId).append(transactionRepository, EntryType.GATEWAY_CLEARING, request.amount(), walletId,
                transaction.id());
        return new Credited(transaction, credit);
    }

    private record Credited(LedgerTransaction transaction, LedgerEntry credit) {
    }

    /**
     * Records the transfer transaction: DEBIT the sender (amount + fee) and CREDIT the receiver (amount) and the revenue
     * account (fee). Only customer wallets can send or receive.
     */
    private TransferResponse moveMoney(String from, String to, long amount, String note) {
        long fee = feeRepository.findAmount(TransactionType.TRANSFER)
                .orElseThrow(() -> new IllegalStateException("No transfer fee configured in the fees table"));
        requireWithinLimit(TransactionType.TRANSFER, amount, fee);
        if (from.equals(to)) {
            throw new SameWalletTransferException(ErrorMessage.SAME_WALLET_TRANSFER);
        }
        Wallet senderWallet = requireCustomerWallet(from);
        Wallet receiverWallet = requireCustomerWallet(to);
        UUID revenue = AppConstants.REVENUE_WALLET_ID;
        Map<UUID, Chain> chains = lockAndLoadChains(new TreeSet<>(List.of(senderWallet.id(), receiverWallet.id(), revenue)));
        Chain sender = chains.get(senderWallet.id());
        Chain receiver = chains.get(receiverWallet.id());
        if (sender.balance < amount + fee) {
            throw new InsufficientBalanceException(ErrorMessage.INSUFFICIENT_BALANCE.formatted(from));
        }

        LedgerTransaction transaction = transactionRepository.insert(
                new LedgerTransaction(UuidGenerator.v7(), TransactionType.TRANSFER, amount, null, null, note, null));
        UUID id = transaction.id();
        LedgerEntry out = sender.append(transactionRepository, EntryType.TRANSFER_OUT, amount, receiverWallet.id(), id);
        sender.append(transactionRepository, EntryType.FEE_OUT, fee, revenue, id);
        receiver.append(transactionRepository, EntryType.TRANSFER_IN, amount, senderWallet.id(), id);
        chains.get(revenue).append(transactionRepository, EntryType.FEE_IN, fee, senderWallet.id(), id);
        return toTransferResponse(transaction, out, fee, from, to);
    }

    /**
     * Checks {@code amount} against the limits table: it must be at least the minimum, and what the customer is
     * charged in total ({@code amount + fee}) must not exceed the single-transaction maximum.
     */
    private void requireWithinLimit(TransactionType type, long amount, long fee) {
        TransactionLimit limit = limitRepository.find(type)
                .orElseThrow(() -> new IllegalStateException("No limit configured in the limits table for " + type));
        if (amount < limit.minAmount()) {
            throw new InvalidAmountException(ErrorMessage.AMOUNT_BELOW_MIN.formatted(
                    limit.minAmount(), bdt(limit.minAmount())));
        }
        if (amount + fee > limit.maxAmount()) {
            long maxAmount = limit.maxAmount() - fee;
            throw new InvalidAmountException(fee == 0
                    ? ErrorMessage.AMOUNT_ABOVE_MAX.formatted(limit.maxAmount(), bdt(limit.maxAmount()))
                    : ErrorMessage.AMOUNT_PLUS_FEE_ABOVE_MAX.formatted(fee, bdt(fee),
                    limit.maxAmount(), bdt(limit.maxAmount()), maxAmount, bdt(maxAmount)));
        }
    }

    private static String bdt(long poisha) {
        return BigDecimal.valueOf(poisha, 2).stripTrailingZeros().toPlainString();
    }

    /**
     * Locks the wallets in ascending id order (the same for every operation, so two operations can never wait on
     * each other) and loads each one's latest ledger position.
     */
    private Map<UUID, Chain> lockAndLoadChains(Set<UUID> walletIds) {
        Map<UUID, Chain> chains = new HashMap<>();
        for (UUID id : new TreeSet<>(walletIds)) {
            WalletType type = walletRepository.lockForUpdate(id)
                    .orElseThrow(() -> new IllegalStateException("Wallet vanished: " + id));
            LedgerEntry latest = transactionRepository.findLatestEntry(id).orElse(null);
            chains.put(id, new Chain(id, type, latest == null ? 0 : latest.entryNo(),
                    latest == null ? 0 : latest.balanceAfter()));
        }
        return chains;
    }

    /** The wallet with this phone, which must exist and be a customer wallet. */
    private Wallet requireCustomerWallet(String phone) {
        Wallet wallet = walletRepository.findByPhone(phone).orElseThrow(() -> new WalletNotFoundException(phone));
        if (wallet.type() != WalletType.LIABILITY) {
            throw new SystemWalletException(ErrorMessage.SYSTEM_WALLET_NOT_ALLOWED.formatted(phone, wallet.type()));
        }
        return wallet;
    }

    /**
     * A wallet's ledger position within the running transaction: advanced as legs are appended, so a wallet can
     * receive several legs in one journal.
     */
    private static final class Chain {
        private final UUID walletId;
        private final WalletType type;
        private long entryNo;
        private long balance;

        private Chain(UUID walletId, WalletType type, long entryNo, long balance) {
            this.walletId = walletId;
            this.type = type;
            this.entryNo = entryNo;
            this.balance = balance;
        }

        LedgerEntry append(TransactionRepository repository, EntryType legType, long amount, UUID counterparty,
                           UUID transactionId) {
            LedgerEntry stored = repository.appendEntry(new LedgerEntry(UuidGenerator.v7(), walletId, type,
                    entryNo + 1, legType, legType.direction(), amount, balance,
                    type.apply(balance, legType.direction(), amount), counterparty, transactionId, null));
            entryNo = stored.entryNo();
            balance = stored.balanceAfter();
            return stored;
        }
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

    private AddMoneyStatusResponse rebuildAddMoney(UUID requestId) {
        AddMoneyRequestRecord request = addMoneyRequestRepository.findById(requestId)
                .orElseThrow(() -> new AddMoneyRequestNotFoundException(requestId.toString()));
        return toAddMoneyStatus(request, phoneOf(request.walletId()));
    }

    private TransferResponse rebuildTransfer(UUID transferId) {
        LedgerEntry out = requireEntry(transferId, EntryType.TRANSFER_OUT);
        long fee = requireEntry(transferId, EntryType.FEE_OUT).amount();
        return toTransferResponse(requireTransaction(transferId), out, fee, phoneOf(out.walletId()),
                phoneOf(out.counterpartyWalletId()));
    }

    private LedgerTransaction requireTransaction(UUID transactionId) {
        return transactionRepository.findTransaction(transactionId)
                .orElseThrow(() -> new IllegalStateException("Missing transaction " + transactionId));
    }

    private String phoneOf(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalStateException("Missing wallet " + walletId)).phone();
    }

    private LedgerEntry requireEntry(UUID transactionId, EntryType type) {
        return transactionRepository.findEntry(transactionId, type)
                .orElseThrow(() -> new IllegalStateException("Missing " + type + " entry of transaction " + transactionId));
    }

    private static DuplicateGatewayRefException duplicateGatewayRef(UUID gatewayRef) {
        return new DuplicateGatewayRefException(ErrorMessage.DUPLICATE_GATEWAY_REF_PREFIX + gatewayRef);
    }

    private AddMoneyStatusResponse toAddMoneyStatus(AddMoneyRequestRecord request, String phone) {
        Long balance = request.transactionId() == null ? null
                : requireEntry(request.transactionId(), EntryType.ADD_MONEY).balanceAfter();
        return new AddMoneyStatusResponse(request.id(), request.status(), request.walletId(), phone, request.amount(),
                request.gatewayRef(), request.paymentMethod(), request.attempts(), request.transactionId(), balance,
                request.errorCode(), request.errorMessage(), request.createdAt(), request.updatedAt());
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

    private static TransferResponse toTransferResponse(LedgerTransaction transaction, LedgerEntry out, long fee,
                                                         String from, String to) {
        return new TransferResponse(transaction.id(), out.walletId(), from, out.counterpartyWalletId(), to,
                transaction.amount(),
                fee, transaction.note(), TRANSFER_STATUS_COMPLETED, transaction.createdAt());
    }

    private static TransactionItemResponse toItem(LedgerEntryView view) {
        LedgerEntry e = view.entry();
        return new TransactionItemResponse(e.id(), e.transactionId(), e.walletId(), view.phone(), e.type(),
                e.direction(), e.amount(), e.balanceAfter(), e.counterpartyWalletId(), view.counterpartyPhone(),
                e.createdAt());
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionPageResponse getTransactions(
            String phone,
            EntryType type,
            Instant from,
            Instant to,
            int page,
            int pageSize
    ) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidDateRangeException();
        }
        UUID walletId = null;
        if (phone != null) {
            String normalized = PhoneNumberUtils.normalize(phone);
            walletId = walletRepository.findByPhone(normalized)
                    .orElseThrow(() -> new WalletNotFoundException(normalized)).id();
        }
        TransactionFilter filter = new TransactionFilter(walletId, type, from, to);
        long totalItems = transactionRepository.count(filter);
        List<TransactionItemResponse> items = transactionRepository
                .search(filter, pageSize, (long) (page - 1) * pageSize).stream()
                .map(DefaultTransactionService::toItem)
                .toList();
        int totalPages = (int) ((totalItems + pageSize - 1) / pageSize);
        return new TransactionPageResponse(items, page, pageSize, totalItems, totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionDetailResponse getTransactionDetails(UUID transactionId) {
        LedgerTransaction transaction = transactionRepository.findTransaction(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId.toString()));
        List<TransactionItemResponse> entries = transactionRepository.findEntries(transactionId).stream()
                .map(DefaultTransactionService::toItem)
                .toList();
        return new TransactionDetailResponse(transaction.id(), transaction.type(), transaction.amount(),
                transaction.gatewayRef(), transaction.paymentMethod(), transaction.note(), transaction.createdAt(), entries);
    }
}
