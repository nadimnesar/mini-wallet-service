package com.pathaopay.miniwallet.service.impl;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.wallet.DuplicatePhoneException;
import com.pathaopay.miniwallet.common.exception.wallet.WalletNotFoundException;
import com.pathaopay.miniwallet.common.util.PhoneNumberUtils;
import com.pathaopay.miniwallet.common.util.UuidGenerator;
import com.pathaopay.miniwallet.dto.response.BalanceResponse;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.response.WalletResponse;
import com.pathaopay.miniwallet.model.enums.Currency;
import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.model.Wallet;
import com.pathaopay.miniwallet.model.WalletBalance;
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
        // The phone is the only constraint: no rule on user_id or on who may create which type for now (role-based
        // permissions will decide that later).
        String phone = PhoneNumberUtils.normalize(request.phone());
        return walletRepository.insertIfAbsent(UuidGenerator.v7(), request.userId(), phone, Currency.BDT, request.type())
                .map(DefaultWalletService::toResponse)
                .map(wallet -> {
                    log.info("Wallet created: phone={} userId={} type={}", wallet.phone(), wallet.userId(), wallet.type());
                    return wallet;
                })
                .orElseThrow(() -> conflictFor(request.userId(), phone));
    }

    /**
     * The insert was skipped because the phone is taken. Runs in the same read-write transaction, so the conflicting
     * (committed) wallet is visible here.
     */
    private RuntimeException conflictFor(UUID userId, String phone) {
        if (walletRepository.existsByPhone(phone)) {
            log.info("Wallet creation rejected, phone already in use: userId={}", userId);
            return new DuplicatePhoneException(ErrorMessage.DUPLICATE_PHONE_PREFIX + phone);
        }
        // Only reachable on a generated wallet-id collision (UUIDv7 has 74 random bits per millisecond).
        return new IllegalStateException("Wallet insert skipped without a phone conflict");
    }

    @Override
    @Transactional(readOnly = true)
    public WalletResponse getWallet(String phone) {
        return toResponse(requireWallet(phone));
    }

    @Override
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String phone) {
        Wallet wallet = requireWallet(phone);
        WalletBalance latest = walletRepository.findLatestBalance(wallet.id())
                .orElse(new WalletBalance(0, wallet.createdAt()));
        return new BalanceResponse(wallet.id(), wallet.phone(), wallet.currency(), latest.balance(), latest.updatedAt());
    }

    private Wallet requireWallet(String phone) {
        String normalized = PhoneNumberUtils.normalize(phone);
        return walletRepository.findByPhone(normalized).orElseThrow(() -> {
            log.info("Wallet not found: phone={}", normalized);
            return new WalletNotFoundException(normalized);
        });
    }

    private static WalletResponse toResponse(Wallet wallet) {
        return new WalletResponse(wallet.id(), wallet.userId(), wallet.phone(), wallet.currency(), wallet.type(),
                wallet.createdAt());
    }
}
