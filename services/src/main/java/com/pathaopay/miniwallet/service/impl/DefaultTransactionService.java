package com.pathaopay.miniwallet.service.impl;

import com.pathaopay.miniwallet.dto.*;
import com.pathaopay.miniwallet.service.TransactionService;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class DefaultTransactionService implements TransactionService {

    @Override
    public AddMoneyResponse addMoney(AddMoneyRequest request, String idempotencyKey) {
        return null;
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
