package com.pathaopay.miniwallet.controller;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.dto.request.AddMoneyRequest;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.request.TransactionHistoryParams;
import com.pathaopay.miniwallet.dto.response.AddMoneyStatusResponse;
import com.pathaopay.miniwallet.dto.response.BalanceResponse;
import com.pathaopay.miniwallet.dto.response.TransactionPageResponse;
import com.pathaopay.miniwallet.dto.response.WalletResponse;
import com.pathaopay.miniwallet.service.TransactionService;
import com.pathaopay.miniwallet.service.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
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
    public ResponseEntity<AddMoneyStatusResponse> addMoney(
            @RequestHeader(name = AppConstants.IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
            @RequestBody @Valid AddMoneyRequest request) {
        AddMoneyStatusResponse response = transactionService.addMoney(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/wallets/add-money/" + response.requestId()))
                .body(response);
    }

    @GetMapping("/add-money/{requestId}")
    public ResponseEntity<AddMoneyStatusResponse> getAddMoneyRequest(@PathVariable("requestId") UUID requestId) {
        return ResponseEntity.ok(transactionService.getAddMoneyRequest(requestId));
    }

    @GetMapping("/{phone}/transactions")
    public ResponseEntity<TransactionPageResponse> getLedgerEntries(
            @PathVariable("phone") String phone,
            @Valid TransactionHistoryParams params) {
        return ResponseEntity.ok(transactionService.getTransactions(phone, params.type(),
                params.from(), params.to(), params.page(), params.pageSize()));
    }
}
