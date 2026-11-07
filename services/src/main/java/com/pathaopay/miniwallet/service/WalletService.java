package com.pathaopay.miniwallet.service;

import com.pathaopay.miniwallet.dto.BalanceResponse;
import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.WalletResponse;

public interface WalletService {
    WalletResponse createWallet(CreateWalletRequest request);

    /** @param phone any accepted format; normalized to 01XXXXXXXXX */
    WalletResponse getWallet(String phone);

    /** @param phone any accepted format; normalized to 01XXXXXXXXX */
    BalanceResponse getBalance(String phone);
}
