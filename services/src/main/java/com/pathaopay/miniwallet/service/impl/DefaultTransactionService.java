package com.pathaopay.miniwallet.service.impl;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.transaction.InsufficientBalanceException;
import com.pathaopay.miniwallet.common.exception.transaction.InvalidDateRangeException;
import com.pathaopay.miniwallet.common.exception.transaction.SameWalletTransferException;
import com.pathaopay.miniwallet.common.exception.transaction.TransactionNotFoundException;
import com.pathaopay.miniwallet.common.exception.wallet.WalletNotFoundException;
import com.pathaopay.miniwallet.common.util.PhoneNumberUtils;
import com.pathaopay.miniwallet.common.util.UuidGenerator;
import com.pathaopay.miniwallet.dto.request.AddMoneyRequest;
import com.pathaopay.miniwallet.dto.request.TransferRequest;
import com.pathaopay.miniwallet.dto.response.AddMoneyStatusResponse;
import com.pathaopay.miniwallet.dto.response.TransactionDetailResponse;
import com.pathaopay.miniwallet.dto.response.TransactionItemResponse;
import com.pathaopay.miniwallet.dto.response.TransactionPageResponse;
import com.pathaopay.miniwallet.dto.response.TransferResponse;
import com.pathaopay.miniwallet.model.AddMoneyRequestRecord;
import com.pathaopay.miniwallet.model.LedgerEntry;
import com.pathaopay.miniwallet.model.LedgerTransaction;
import com.pathaopay.miniwallet.model.TransactionFilter;
import com.pathaopay.miniwallet.model.Wallet;
import com.pathaopay.miniwallet.model.enums.AddMoneyStatus;
import com.pathaopay.miniwallet.model.enums.EntryType;
import com.pathaopay.miniwallet.model.enums.TransactionType;
import com.pathaopay.miniwallet.repository.AddMoneyRequestRepository;
import com.pathaopay.miniwallet.repository.FeeRepository;
import com.pathaopay.miniwallet.repository.TransactionRepository;
import com.pathaopay.miniwallet.repository.WalletRepository;
import com.pathaopay.miniwallet.service.TransactionService;
import com.pathaopay.miniwallet.service.addmoney.AddMoneyProcessor;
import com.pathaopay.miniwallet.service.idempotency.IdempotentExecutor;
import com.pathaopay.miniwallet.service.ledger.LedgerPoster;
import com.pathaopay.miniwallet.service.mapper.TransactionResponses;
import com.pathaopay.miniwallet.service.rules.TransactionRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static com.pathaopay.miniwallet.service.rules.TransactionRules.duplicateGatewayRef;

/**
 * The public money operations. The heavy lifting lives in focused helpers: {@link IdempotentExecutor} (the
 * Idempotency-Key engine), {@link LedgerPoster} (wallet locks and ledger legs), {@link TransactionRules} (limits and
 * wallet checks), {@link AddMoneyProcessor} (the add-money worker) and {@link TransactionResponses} (response
 * building).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultTransactionService implements TransactionService {

    private static final String ADD_MONEY_OPERATION = "ADD_MONEY";
    private static final String TRANSFER_OPERATION = "TRANSFER";

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final FeeRepository feeRepository;
    private final AddMoneyRequestRepository addMoneyRequestRepository;
    private final IdempotentExecutor idempotentExecutor;
    private final LedgerPoster ledgerPoster;
    private final TransactionRules rules;
    private final AddMoneyProcessor addMoneyProcessor;
    private final TransactionResponses responses;

    /**
     * Accepts an add-money request: checks everything that needs no gateway (amount limits, a customer wallet, an
     * unused gateway_ref) and stores a PENDING request, all under the Idempotency-Key. The gateway call and the
     * credit happen later in {@link #processAddMoney}, so no connection is held while the gateway answers.
     *
     * @see IdempotentExecutor#execute
     */
    @Override
    public AddMoneyStatusResponse addMoney(AddMoneyRequest request, String idempotencyKey) {
        String phone = PhoneNumberUtils.normalize(request.phone());
        String requestHash = IdempotentExecutor.fingerprint(ADD_MONEY_OPERATION, phone,
                request.amount().toString(), request.gatewayRef().toString(), request.paymentMethod().name());

        return idempotentExecutor.execute(idempotencyKey, ADD_MONEY_OPERATION, requestHash, () -> {
            rules.requireWithinLimit(TransactionType.ADD_MONEY, request.amount());
            Wallet wallet = rules.requireCustomerWallet(phone);
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
            return new IdempotentExecutor.Created<>(accepted.id(), responses.addMoneyStatus(accepted, phone));
        }, responses::addMoneyStatus);
    }

    @Override
    @Transactional(readOnly = true)
    public AddMoneyStatusResponse getAddMoneyRequest(UUID requestId) {
        return responses.addMoneyStatus(requestId);
    }

    @Override
    public void processAddMoney(UUID requestId) {
        addMoneyProcessor.process(requestId);
    }

    /**
     * Moves money between two customer wallets: one TRANSFER transaction (its id is the transfer id) and four ledger
     * entries (TRANSFER_OUT and FEE_OUT on the sender, TRANSFER_IN on the receiver, FEE_IN on the revenue account) are
     * written in one database transaction, so either all exist or none does. Wallet rows are locked in ascending id order, so
     * opposite transfers between the same two wallets cannot deadlock.
     *
     * @see IdempotentExecutor#execute
     */
    @Override
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        String from = PhoneNumberUtils.normalize(request.fromPhone());
        String to = PhoneNumberUtils.normalize(request.toPhone());
        String requestHash = IdempotentExecutor.fingerprint(TRANSFER_OPERATION, from, to,
                request.amount().toString(), request.note() == null ? "" : request.note());

        return idempotentExecutor.execute(idempotencyKey, TRANSFER_OPERATION, requestHash, () -> {
            TransferResponse response = moveMoney(from, to, request.amount(), request.note());
            log.info("Transferred money: transferId={} from={} to={} amount={}",
                    response.transferId(), from, to, response.amount());
            return new IdempotentExecutor.Created<>(response.transferId(), response);
        }, responses::transfer);
    }

    /**
     * Records the transfer transaction: DEBIT the sender (amount + fee) and CREDIT the receiver (amount) and the revenue
     * account (fee). Only customer wallets can send or receive.
     */
    private TransferResponse moveMoney(String from, String to, long amount, String note) {
        long fee = feeRepository.findAmount(TransactionType.TRANSFER)
                .orElseThrow(() -> new IllegalStateException("No transfer fee configured in the fees table"));
        rules.requireWithinLimit(TransactionType.TRANSFER, amount);
        if (from.equals(to)) {
            throw new SameWalletTransferException(ErrorMessage.SAME_WALLET_TRANSFER);
        }
        Wallet senderWallet = rules.requireCustomerWallet(from);
        Wallet receiverWallet = rules.requireCustomerWallet(to);
        UUID revenue = AppConstants.REVENUE_WALLET_ID;
        Map<UUID, LedgerPoster.Chain> chains = ledgerPoster.lockAndLoadChains(
                new TreeSet<>(List.of(senderWallet.id(), receiverWallet.id(), revenue)));
        LedgerPoster.Chain sender = chains.get(senderWallet.id());
        LedgerPoster.Chain receiver = chains.get(receiverWallet.id());
        // Subtract instead of adding amount + fee, which could overflow (balance and fee are never negative).
        if (sender.balance() - fee < amount) {
            throw new InsufficientBalanceException(ErrorMessage.INSUFFICIENT_BALANCE.formatted(from));
        }

        LedgerTransaction transaction = transactionRepository.insert(
                new LedgerTransaction(UuidGenerator.v7(), TransactionType.TRANSFER, amount, null, null, note, null));
        UUID id = transaction.id();
        LedgerEntry out = sender.append(EntryType.TRANSFER_OUT, amount, receiverWallet.id(), id);
        sender.append(EntryType.FEE_OUT, fee, revenue, id);
        receiver.append(EntryType.TRANSFER_IN, amount, senderWallet.id(), id);
        chains.get(revenue).append(EntryType.FEE_IN, fee, senderWallet.id(), id);
        return responses.transfer(transaction, out, fee, from, to);
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
        String normalized = PhoneNumberUtils.normalize(phone);
        UUID walletId = walletRepository.findByPhone(normalized)
                .orElseThrow(() -> new WalletNotFoundException(normalized)).id();
        TransactionFilter filter = new TransactionFilter(walletId, type, from, to);
        long totalItems = transactionRepository.count(filter);
        List<TransactionItemResponse> items = transactionRepository
                .search(filter, pageSize, (long) (page - 1) * pageSize).stream()
                .map(TransactionResponses::item)
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
                .map(TransactionResponses::item)
                .toList();
        return new TransactionDetailResponse(transaction.id(), transaction.type(), transaction.amount(),
                transaction.gatewayRef(), transaction.paymentMethod(), transaction.note(), transaction.createdAt(), entries);
    }
}
