package com.pathaopay.miniwallet.service.impl;

import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.WalletResponse;
import com.pathaopay.miniwallet.service.WalletService;
import org.springframework.stereotype.Service;

@Service
public class DefaultWalletService implements WalletService {

    @Override
    public WalletResponse createWallet(CreateWalletRequest request) {
        return null;
    }

    @Override
    public WalletResponse getWallet(String walletId) {
        return null;
    }
}
