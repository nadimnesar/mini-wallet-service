package com.pathaopay.miniwallet.service;

import com.pathaopay.miniwallet.dto.response.BalanceResponse;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.response.WalletResponse;

/** Wallet creation and lookup. Wallets are identified externally by phone number. */
public interface WalletService {
    /** Creates a wallet of the requested type; the phone must be valid and not yet used by another wallet. */
    WalletResponse createWallet(CreateWalletRequest request);

    /**
     * Wallet details (no balance).
     *
     * @param phone any accepted format; normalized to 01XXXXXXXXX
     */
    WalletResponse getWallet(String phone);

    /**
     * Current balance, taken from the wallet's latest ledger entry (0 if it has none).
     *
     * @param phone any accepted format; normalized to 01XXXXXXXXX
     */
    BalanceResponse getBalance(String phone);
}
