package io.aevumpay.chain;

public record SendResult(String txHash, Long chainInvoiceId, boolean success, String error) {}
