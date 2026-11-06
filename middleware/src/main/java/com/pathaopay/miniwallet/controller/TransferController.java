package com.pathaopay.miniwallet.controller;

import com.pathaopay.miniwallet.dto.TransferRequest;
import com.pathaopay.miniwallet.dto.TransferResponse;
import com.pathaopay.miniwallet.service.TransactionService;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/v1/transfers")
@Validated
@RequiredArgsConstructor
public class TransferController {

    private final TransactionService transactionService;

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader(name = AppConstants.IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
            @RequestBody @Valid TransferRequest request) {
        TransferResponse response = transactionService.transfer(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
