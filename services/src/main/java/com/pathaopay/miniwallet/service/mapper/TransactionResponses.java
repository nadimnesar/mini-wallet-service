package com.pathaopay.miniwallet.service.mapper;

import com.pathaopay.miniwallet.common.exception.transaction.AddMoneyRequestNotFoundException;
import com.pathaopay.miniwallet.dto.response.AddMoneyStatusResponse;
import com.pathaopay.miniwallet.dto.response.TransactionItemResponse;
import com.pathaopay.miniwallet.dto.response.TransferResponse;
import com.pathaopay.miniwallet.model.AddMoneyRequestRecord;
import com.pathaopay.miniwallet.model.LedgerEntry;
import com.pathaopay.miniwallet.model.LedgerEntryView;
import com.pathaopay.miniwallet.model.LedgerTransaction;
import com.pathaopay.miniwallet.model.enums.EntryType;
import com.pathaopay.miniwallet.repository.AddMoneyRequestRepository;
import com.pathaopay.miniwallet.repository.TransactionRepository;
import com.pathaopay.miniwallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Builds API responses from stored state. Everything is derived from the immutable ledger and the request row, so an
 * idempotent replay rebuilds exactly what the original call returned.
 */
@Component
@RequiredArgsConstructor
public class TransactionResponses {

    private static final String TRANSFER_STATUS_COMPLETED = "COMPLETED";

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final AddMoneyRequestRepository addMoneyRequestRepository;

    /** Status of the add-money request with this id. */
    public AddMoneyStatusResponse addMoneyStatus(UUID requestId) {
        AddMoneyRequestRecord request = addMoneyRequestRepository.findById(requestId)
                .orElseThrow(() -> new AddMoneyRequestNotFoundException(requestId.toString()));
        return addMoneyStatus(request, phoneOf(request.walletId()));
    }

    /** Status of an already loaded request; the balance is the one after the credit, once the request is completed. */
    public AddMoneyStatusResponse addMoneyStatus(AddMoneyRequestRecord request, String phone) {
        Long balance = request.transactionId() == null ? null
                : requireEntry(request.transactionId(), EntryType.ADD_MONEY).balanceAfter();
        return new AddMoneyStatusResponse(request.id(), request.status(), request.walletId(), phone, request.amount(),
                request.gatewayRef(), request.paymentMethod(), request.attempts(), request.transactionId(), balance,
                request.errorCode(), request.errorMessage(), request.createdAt(), request.updatedAt());
    }

    /** Rebuilds a transfer response from the stored ledger legs (used for idempotent replays). */
    public TransferResponse transfer(UUID transferId) {
        LedgerEntry out = requireEntry(transferId, EntryType.TRANSFER_OUT);
        long fee = requireEntry(transferId, EntryType.FEE_OUT).amount();
        return transfer(requireTransaction(transferId), out, fee, phoneOf(out.walletId()),
                phoneOf(out.counterpartyWalletId()));
    }

    /** Builds a transfer response from the posted transaction and its {@code TRANSFER_OUT} leg. */
    public TransferResponse transfer(LedgerTransaction transaction, LedgerEntry out, long fee, String from, String to) {
        return new TransferResponse(transaction.id(), out.walletId(), from, out.counterpartyWalletId(), to,
                transaction.amount(), fee, transaction.note(), TRANSFER_STATUS_COMPLETED, transaction.createdAt());
    }

    /** One ledger leg as a history item. */
    public static TransactionItemResponse item(LedgerEntryView view) {
        LedgerEntry e = view.entry();
        return new TransactionItemResponse(e.id(), e.transactionId(), e.walletId(), view.phone(), e.type(),
                e.direction(), e.amount(), e.balanceAfter(), e.counterpartyWalletId(), view.counterpartyPhone(),
                e.createdAt());
    }

    /** The phone of a wallet that must exist. */
    public String phoneOf(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalStateException("Missing wallet " + walletId)).phone();
    }

    private LedgerTransaction requireTransaction(UUID transactionId) {
        return transactionRepository.findTransaction(transactionId)
                .orElseThrow(() -> new IllegalStateException("Missing transaction " + transactionId));
    }

    private LedgerEntry requireEntry(UUID transactionId, EntryType type) {
        return transactionRepository.findEntry(transactionId, type)
                .orElseThrow(() -> new IllegalStateException("Missing " + type + " entry of transaction " + transactionId));
    }
}
