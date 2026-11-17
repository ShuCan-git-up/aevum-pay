package io.aevumpay.chain;

import java.math.BigInteger;

public record OnChainInvoice(
        String merchant,
        long dueAt,
        boolean allowPartial,
        int status,
        String payer,
        long createdAt,
        BigInteger amount,
        BigInteger paidAmount,
        BigInteger refundedAmount,
        String orderRef) {}
