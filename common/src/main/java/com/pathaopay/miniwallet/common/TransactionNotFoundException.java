package com.pathaopay.miniwallet.common;

public class TransactionNotFoundException extends MiniWalletException {
    public TransactionNotFoundException(String transactionId) {
        super("TRANSACTION_NOT_FOUND", "Transaction not found with id: " + transactionId);
    }
}
