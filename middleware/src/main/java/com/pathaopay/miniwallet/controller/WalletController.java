package com.pathaopay.miniwallet.controller;

import com.pathaopay.miniwallet.dto.AddMoneyRequest;
import com.pathaopay.miniwallet.dto.AddMoneyResponse;
import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.WalletResponse;
import com.pathaopay.miniwallet.service.TransactionService;
import com.pathaopay.miniwallet.service.WalletService;
import jakarta.validation.Valid;
import com.pathaopay.miniwallet.dto.TransactionPageResponse;
import com.pathaopay.miniwallet.model.TransactionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/wallets")
@Validated
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final TransactionService transactionService;

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@RequestBody @Valid CreateWalletRequest request) {
        WalletResponse response = walletService.createWallet(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{walletId}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable("walletId") UUID walletId) {
        WalletResponse response = walletService.getWallet(walletId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/add-money")
    public ResponseEntity<AddMoneyResponse> addMoney(
            @RequestHeader(name = "Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @RequestBody @Valid AddMoneyRequest request) {
        AddMoneyResponse response = transactionService.addMoney(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{walletId}/transactions")
    public ResponseEntity<TransactionPageResponse> getWalletTransactions(
            @PathVariable("walletId") UUID walletId,

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
        return ResponseEntity.ok(transactionService.getTransactions(walletId, type, from, to, page, pageSize));
    }
}
