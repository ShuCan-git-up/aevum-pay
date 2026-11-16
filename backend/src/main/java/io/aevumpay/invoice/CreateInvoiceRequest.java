package io.aevumpay.invoice;

import java.time.Instant;

public record CreateInvoiceRequest(
        String orderId, String amount, Instant dueAt, boolean allowPartial, String payer) {}
