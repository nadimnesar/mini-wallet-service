package com.pathaopay.miniwallet.controller;

import com.pathaopay.miniwallet.dto.response.TransactionDetailResponse;
import com.pathaopay.miniwallet.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Transaction lookup endpoints under {@code /api/v1/transactions}. */
@RestController
@RequestMapping(path = "/api/v1/transactions")
@Validated
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    /** {@code GET /api/v1/transactions/{transactionId}}: returns one transaction with its ledger legs. */
    @GetMapping(path = "/{transactionId}")
    public ResponseEntity<TransactionDetailResponse> getTransactionDetails(
            @PathVariable("transactionId") UUID transactionId) {
        TransactionDetailResponse response = transactionService.getTransactionDetails(transactionId);
        return ResponseEntity.ok(response);
    }
}
