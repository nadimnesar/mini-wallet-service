package com.pathaopay.miniwallet.service;

import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.WalletResponse;

import java.util.UUID;

public interface WalletService {
    WalletResponse createWallet(CreateWalletRequest request);

    WalletResponse getWallet(UUID walletId);
}
