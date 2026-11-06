package com.pathaopay.miniwallet.service.impl;

import com.pathaopay.miniwallet.common.DuplicateWalletException;
import com.pathaopay.miniwallet.common.WalletNotFoundException;
import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorMessages;
import com.pathaopay.miniwallet.common.constant.AppConstants.Ids;
import com.pathaopay.miniwallet.common.util.PhoneNumberUtils;
import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.WalletResponse;
import com.pathaopay.miniwallet.model.Currency;
import com.pathaopay.miniwallet.model.Wallet;
import com.pathaopay.miniwallet.repository.WalletRepository;
import com.pathaopay.miniwallet.service.WalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DefaultWalletService implements WalletService {

    private final WalletRepository walletRepository;

    public DefaultWalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Override
    @Transactional
    public WalletResponse createWallet(CreateWalletRequest request) {
        String phone = PhoneNumberUtils.normalize(request.phone());
        return walletRepository.insertIfUserHasNone(newWalletId(), request.userId(), phone, Currency.BDT)
                .map(DefaultWalletService::toResponse)
                .orElseThrow(() -> new DuplicateWalletException(ErrorMessages.DUPLICATE_WALLET_PREFIX + request.userId()));
    }

    /** Served by the read replica; the balance may lag the primary by the replication delay. */
    @Override
    @Transactional(readOnly = true)
    public WalletResponse getWallet(String walletId) {
        return walletRepository.findById(walletId)
                .map(DefaultWalletService::toResponse)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    private static String newWalletId() {
        return Ids.WALLET_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private static WalletResponse toResponse(Wallet wallet) {
        return new WalletResponse(wallet.id(), wallet.userId(), wallet.currency(), wallet.balance(), wallet.createdAt());
    }
}
