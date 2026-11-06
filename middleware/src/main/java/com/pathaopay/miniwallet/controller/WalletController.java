package com.pathaopay.miniwallet.controller;

import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.WalletResponse;
import com.pathaopay.miniwallet.service.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/v1/wallets", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<WalletResponse> createWallet(@RequestBody @Valid CreateWalletRequest request) {
        WalletResponse response = walletService.createWallet(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{walletId}")
    public ResponseEntity<WalletResponse> getWallet(
            @PathVariable("walletId")
            @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,64}$", message = "Invalid wallet_id format")
            String walletId) {
        WalletResponse response = walletService.getWallet(walletId);
        return ResponseEntity.ok(response);
    }
}
