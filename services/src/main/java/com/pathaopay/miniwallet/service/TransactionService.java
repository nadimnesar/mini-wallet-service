package com.pathaopay.miniwallet.service;

import com.pathaopay.miniwallet.dto.*;

import java.time.Instant;

public interface TransactionService {
    AddMoneyResponse addMoney(AddMoneyRequest request, String idempotencyKey);

    TransferResponse transfer(TransferRequest request, String idempotencyKey);

    TransactionPageResponse getTransactions(
            String walletId,
            String type,
            Instant from,
            Instant to,
            int page,
            int pageSize
    );

    TransactionDetailResponse getTransactionDetails(String transactionId);
}
