package com.pathaopay.miniwallet.service;

import com.pathaopay.miniwallet.dto.request.*;
import com.pathaopay.miniwallet.dto.response.*;

import com.pathaopay.miniwallet.model.enums.EntryType;

import java.time.Instant;
import java.util.UUID;

public interface TransactionService {
    AddMoneyResponse addMoney(AddMoneyRequest request, String idempotencyKey);

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
