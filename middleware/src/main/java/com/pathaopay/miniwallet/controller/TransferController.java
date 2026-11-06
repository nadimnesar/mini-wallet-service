package com.pathaopay.miniwallet.controller;

import com.pathaopay.miniwallet.dto.TransferRequest;
import com.pathaopay.miniwallet.dto.TransferResponse;
import com.pathaopay.miniwallet.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/v1/transfers")
@Validated
public class TransferController {

    private final TransactionService transactionService;

    public TransferController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid TransferRequest request) {
        TransferResponse response = transactionService.transfer(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
