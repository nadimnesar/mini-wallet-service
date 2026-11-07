package com.pathaopay.miniwallet.controller;

import com.pathaopay.miniwallet.dto.*;
import com.pathaopay.miniwallet.model.EntryType;
import com.pathaopay.miniwallet.service.TransactionService;
import com.pathaopay.miniwallet.service.WalletService;
import jakarta.validation.Valid;
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

    @GetMapping("/{phone}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable("phone") String phone) {
        WalletResponse response = walletService.getWallet(phone);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{phone}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable("phone") String phone) {
        return ResponseEntity.ok(walletService.getBalance(phone));
    }

    @PostMapping("/add-money")
    public ResponseEntity<AddMoneyResponse> addMoney(
            @RequestHeader(name = "Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @RequestBody @Valid AddMoneyRequest request) {
        AddMoneyResponse response = transactionService.addMoney(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{phone}/transactions")
    public ResponseEntity<TransactionPageResponse> getLedgerEntries(
            @PathVariable("phone") String phone,

            @RequestParam(name = "type", required = false)
            EntryType type,

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
        return ResponseEntity.ok(transactionService.getTransactions(phone, type, from, to, page, pageSize));
    }
}
