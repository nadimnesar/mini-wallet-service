package com.pathaopay.miniwallet.service.impl;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.wallet.DuplicatePhoneException;
import com.pathaopay.miniwallet.common.exception.wallet.DuplicateWalletException;
import com.pathaopay.miniwallet.common.exception.wallet.WalletNotFoundException;
import com.pathaopay.miniwallet.common.util.PhoneNumberUtils;
import com.pathaopay.miniwallet.common.util.UuidGenerator;
import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.WalletResponse;
import com.pathaopay.miniwallet.model.Currency;
import com.pathaopay.miniwallet.model.Wallet;
import com.pathaopay.miniwallet.repository.WalletRepository;
import com.pathaopay.miniwallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultWalletService implements WalletService {

    private final WalletRepository walletRepository;

    @Override
    @Transactional
    public WalletResponse createWallet(CreateWalletRequest request) {
        String phone = PhoneNumberUtils.normalize(request.phone());
        if (AppConstants.SYSTEM_USER_ID.equals(request.userId())) {
            log.info("Wallet creation rejected, user_id is reserved for system accounts");
            throw new DuplicateWalletException(ErrorMessage.DUPLICATE_WALLET_PREFIX + request.userId());
        }
        return walletRepository.insertIfAbsent(UuidGenerator.v7(), request.userId(), phone, Currency.BDT)
                .map(DefaultWalletService::toResponse)
                .map(wallet -> {
                    log.info("Wallet created: phone={} userId={}", wallet.phone(), wallet.userId());
                    return wallet;
                })
                .orElseThrow(() -> conflictFor(request.userId(), phone));
    }

    /**
     * The insert was skipped because a unique constraint matched. Runs in the same read-write
     * transaction on the primary, so the conflicting (committed) wallet is visible here.
     */
    private RuntimeException conflictFor(UUID userId, String phone) {
        if (walletRepository.existsByUserId(userId)) {
            log.info("Wallet creation rejected, user already has a wallet: userId={}", userId);
            return new DuplicateWalletException(ErrorMessage.DUPLICATE_WALLET_PREFIX + userId);
        }
        if (walletRepository.existsByPhone(phone)) {
            log.info("Wallet creation rejected, phone already in use: userId={}", userId);
            return new DuplicatePhoneException(ErrorMessage.DUPLICATE_PHONE_PREFIX + phone);
        }
        // Only reachable on a generated wallet-id collision (UUIDv7 has 74 random bits per millisecond).
        return new IllegalStateException("Wallet insert skipped without a user_id or phone conflict");
    }

    /**
     * Served by the read replica; the balance may lag the primary by the replication delay.
     */
    @Override
    @Transactional(readOnly = true)
    public WalletResponse getWallet(String phone) {
        String normalized = PhoneNumberUtils.normalize(phone);
        return walletRepository.findByPhone(normalized)
                .map(DefaultWalletService::toResponse)
                .orElseThrow(() -> {
                    log.info("Wallet not found: phone={}", normalized);
                    return new WalletNotFoundException(normalized);
                });
    }

    private static WalletResponse toResponse(Wallet wallet) {
        return new WalletResponse(wallet.userId(), wallet.phone(), wallet.currency(), wallet.type(), wallet.balance(), wallet.createdAt());
    }
}
