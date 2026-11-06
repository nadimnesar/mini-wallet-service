package com.pathaopay.miniwallet.controller;

import com.pathaopay.miniwallet.dto.TransactionDetailResponse;
import com.pathaopay.miniwallet.dto.TransactionPageResponse;
import com.pathaopay.miniwallet.model.TransactionType;
import com.pathaopay.miniwallet.service.TransactionService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/transactions")
@Validated
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping
    public ResponseEntity<TransactionPageResponse> listTransactions(
            @RequestParam(name = "wallet_id", required = false)
            UUID walletId,

            @RequestParam(name = "type", required = false)
            TransactionType type,

            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,

            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,

            @RequestParam(name = "page", defaultValue = "1")
            @Min(value = 1, message = "page must be at least 1")
            int page,

            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "page_size must be at least 1")
            @Max(value = 100, message = "page_size cannot exceed 100")
            int pageSize) {
        TransactionPageResponse response = transactionService.getTransactions(walletId, type, from, to, page, pageSize);
        return ResponseEntity.ok(response);
    }

    @GetMapping(path = "/{transactionId}")
    public ResponseEntity<TransactionDetailResponse> getTransactionDetails(
            @PathVariable("transactionId") UUID transactionId) {
        TransactionDetailResponse response = transactionService.getTransactionDetails(transactionId);
        return ResponseEntity.ok(response);
    }
}
