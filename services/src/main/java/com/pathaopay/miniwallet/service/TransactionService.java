package com.pathaopay.miniwallet.service;

import com.pathaopay.miniwallet.dto.*;

import com.pathaopay.miniwallet.model.TransactionType;

import java.time.Instant;
import java.util.UUID;

public interface TransactionService {
    AddMoneyResponse addMoney(AddMoneyRequest request, String idempotencyKey);

    TransferResponse transfer(TransferRequest request, String idempotencyKey);

    TransactionPageResponse getTransactions(
            UUID walletId,
            TransactionType type,
            Instant from,
            Instant to,
            int page,
            int pageSize
    );

    TransactionDetailResponse getTransactionDetails(UUID transactionId);
}
