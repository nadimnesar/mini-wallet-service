package com.pathaopay.miniwallet.service.rules;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.payment.DuplicateGatewayRefException;
import com.pathaopay.miniwallet.common.exception.transaction.InvalidAmountException;
import com.pathaopay.miniwallet.common.exception.transaction.SystemWalletException;
import com.pathaopay.miniwallet.common.exception.wallet.WalletNotFoundException;
import com.pathaopay.miniwallet.model.TransactionLimit;
import com.pathaopay.miniwallet.model.Wallet;
import com.pathaopay.miniwallet.model.enums.TransactionType;
import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.repository.LimitRepository;
import com.pathaopay.miniwallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/** Business rules checked before money is accepted or moved. */
@Component
@RequiredArgsConstructor
public class TransactionRules {

    private final WalletRepository walletRepository;
    private final LimitRepository limitRepository;

    /**
     * Checks {@code amount} against the limits table: it must be at least the minimum, and what the customer is
     * charged in total ({@code amount + fee}) must not exceed the single-transaction maximum. The sum is never
     * computed: for an amount near {@code Long.MAX_VALUE} it would overflow and pass the check.
     */
    public void requireWithinLimit(TransactionType type, long amount, long fee) {
        TransactionLimit limit = limitRepository.find(type)
                .orElseThrow(() -> new IllegalStateException("No limit configured in the limits table for " + type));
        if (amount < limit.minAmount()) {
            throw new InvalidAmountException(ErrorMessage.AMOUNT_BELOW_MIN.formatted(
                    limit.minAmount(), bdt(limit.minAmount())));
        }
        long maxAmount = limit.maxAmount() - fee;
        if (amount > maxAmount) {
            throw new InvalidAmountException(fee == 0
                    ? ErrorMessage.AMOUNT_ABOVE_MAX.formatted(limit.maxAmount(), bdt(limit.maxAmount()))
                    : ErrorMessage.AMOUNT_PLUS_FEE_ABOVE_MAX.formatted(fee, bdt(fee),
                    limit.maxAmount(), bdt(limit.maxAmount()), maxAmount, bdt(maxAmount)));
        }
    }

    /** The wallet with this phone, which must exist and be a customer wallet. */
    public Wallet requireCustomerWallet(String phone) {
        Wallet wallet = walletRepository.findByPhone(phone).orElseThrow(() -> new WalletNotFoundException(phone));
        if (wallet.type() != WalletType.LIABILITY) {
            throw new SystemWalletException(ErrorMessage.SYSTEM_WALLET_NOT_ALLOWED.formatted(phone, wallet.type()));
        }
        return wallet;
    }

    public static DuplicateGatewayRefException duplicateGatewayRef(UUID gatewayRef) {
        return new DuplicateGatewayRefException(ErrorMessage.DUPLICATE_GATEWAY_REF_PREFIX + gatewayRef);
    }

    private static String bdt(long poisha) {
        return BigDecimal.valueOf(poisha, 2).stripTrailingZeros().toPlainString();
    }
}
