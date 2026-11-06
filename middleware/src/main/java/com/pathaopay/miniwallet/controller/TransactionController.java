package com.pathaopay.miniwallet.controller;

import com.pathaopay.miniwallet.dto.*;
import com.pathaopay.miniwallet.service.TransactionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping(path = "/api/v1")
@Validated
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping(path = "/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid TransferRequest request) {
        TransferResponse response = transactionService.transfer(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping(path = "/transactions")
    public ResponseEntity<TransactionPageResponse> listTransactions(
            @RequestParam(name = "wallet_id", required = false)
            String walletId,

            @RequestParam(name = "type", required = false)
            String type,

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

    @GetMapping(path = "/transactions/{transactionId}")
    public ResponseEntity<TransactionDetailResponse> getTransactionDetails(
            @PathVariable("transactionId") String transactionId) {
        TransactionDetailResponse response = transactionService.getTransactionDetails(transactionId);
        return ResponseEntity.ok(response);
    }
}
