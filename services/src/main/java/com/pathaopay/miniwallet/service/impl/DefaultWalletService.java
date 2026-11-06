package com.pathaopay.miniwallet.service.impl;

import com.pathaopay.miniwallet.common.DuplicatePhoneException;
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
        return walletRepository.insertIfAbsent(newWalletId(), request.userId(), phone, Currency.BDT)
                .map(DefaultWalletService::toResponse)
                .orElseThrow(() -> conflictFor(request.userId(), phone));
    }

    /**
     * The insert was skipped because a unique constraint matched. Runs in the same read-write
     * transaction on the primary, so the conflicting (committed) wallet is visible here.
     */
    private RuntimeException conflictFor(String userId, String phone) {
        if (walletRepository.existsByUserId(userId)) {
            return new DuplicateWalletException(ErrorMessages.DUPLICATE_WALLET_PREFIX + userId);
        }
        if (walletRepository.existsByPhone(phone)) {
            return new DuplicatePhoneException(ErrorMessages.DUPLICATE_PHONE_PREFIX + phone);
        }
        // Only reachable on a generated wallet-id collision (random 128-bit UUID).
        return new IllegalStateException("Wallet insert skipped without a user_id or phone conflict");
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
