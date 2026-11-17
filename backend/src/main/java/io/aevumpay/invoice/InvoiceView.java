package io.aevumpay.invoice;

import java.time.Instant;

public record InvoiceView(
        Long localId,
        Long chainInvoiceId,
        String orderId,
        String orderRef,
        String merchant,
        String payer,
        String amount,
        String paidAmount,
        String refundedAmount,
        String remaining,
        boolean allowPartial,
        Instant dueAt,
        String status,
        String createTxHash,
        String lastTxHash,
        String payUrl,
        String explorerTxUrl,
        Instant updatedAt) {}
