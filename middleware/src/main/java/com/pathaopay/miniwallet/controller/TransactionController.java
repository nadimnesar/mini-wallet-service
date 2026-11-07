package com.pathaopay.miniwallet.controller;

import com.pathaopay.miniwallet.dto.response.TransactionDetailResponse;
import com.pathaopay.miniwallet.dto.response.TransactionPageResponse;
import com.pathaopay.miniwallet.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/transactions")
@Validated
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping
    public ResponseEntity<TransactionPageResponse> listTransactions(@Valid TransactionHistoryParams params) {
        return ResponseEntity.ok(transactionService.getTransactions(params.phone(), params.type(), params.from(),
                params.to(), params.page(), params.pageSize()));
    }

    @GetMapping(path = "/{transactionId}")
    public ResponseEntity<TransactionDetailResponse> getTransactionDetails(
            @PathVariable("transactionId") UUID transactionId) {
        TransactionDetailResponse response = transactionService.getTransactionDetails(transactionId);
        return ResponseEntity.ok(response);
    }
}
