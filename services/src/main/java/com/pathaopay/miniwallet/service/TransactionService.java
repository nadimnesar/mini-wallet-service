package com.pathaopay.miniwallet.service;

import com.pathaopay.miniwallet.dto.request.AddMoneyRequest;
import com.pathaopay.miniwallet.dto.request.TransferRequest;
import com.pathaopay.miniwallet.dto.response.AddMoneyStatusResponse;
import com.pathaopay.miniwallet.dto.response.TransactionDetailResponse;
import com.pathaopay.miniwallet.dto.response.TransactionPageResponse;
import com.pathaopay.miniwallet.dto.response.TransferResponse;
import com.pathaopay.miniwallet.model.enums.EntryType;

import java.time.Instant;
import java.util.UUID;

public interface TransactionService {
    /**
     * Validates and accepts an add-money request; the card gateway is checked and the wallet credited later by
     * {@link #processAddMoney}. Returns the PENDING request (the stored state again on an idempotent replay).
     */
    AddMoneyStatusResponse addMoney(AddMoneyRequest request, String idempotencyKey);

    AddMoneyStatusResponse getAddMoneyRequest(UUID requestId);

    /**
     * Worker side of add-money: verifies the payment and credits the wallet, or records the failure. A retryable
     * failure is scheduled for another attempt until the retries run out. Safe to call again for the same request
     * (a duplicate delivery): a request that is no longer PENDING is skipped.
     */
    void processAddMoney(UUID requestId);

    TransferResponse transfer(TransferRequest request, String idempotencyKey);

    TransactionPageResponse getTransactions(
            String phone,
            EntryType type,
            Instant from,
            Instant to,
            int page,
            int pageSize
    );

    TransactionDetailResponse getTransactionDetails(UUID transactionId);
}
