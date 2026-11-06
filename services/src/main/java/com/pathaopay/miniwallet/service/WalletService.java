package com.pathaopay.miniwallet.service;

import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.WalletResponse;

public interface WalletService {
    WalletResponse createWallet(CreateWalletRequest request);

    WalletResponse getWallet(String walletId);
}
