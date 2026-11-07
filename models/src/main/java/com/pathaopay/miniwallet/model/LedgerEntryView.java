package com.pathaopay.miniwallet.model;

/** A ledger entry with the phone numbers of its wallet and counterparty, for the external API. */
public record LedgerEntryView(LedgerEntry entry, String phone, String counterpartyPhone) {
}
