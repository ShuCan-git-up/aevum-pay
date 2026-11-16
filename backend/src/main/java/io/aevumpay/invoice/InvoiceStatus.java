package io.aevumpay.invoice;

public enum InvoiceStatus {
    CREATING,
    PENDING,
    PARTIALLY_PAID,
    PAID,
    EXPIRED,
    CANCELLED,
    REFUNDED,
    FAILED
}
