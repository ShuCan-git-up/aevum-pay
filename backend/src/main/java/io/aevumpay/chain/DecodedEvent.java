package io.aevumpay.chain;

import java.util.Map;

public record DecodedEvent(
        String eventName,
        String txHash,
        int logIndex,
        long blockNumber,
        String blockHash,
        long chainInvoiceId,
        Map<String, Object> fields) {}
