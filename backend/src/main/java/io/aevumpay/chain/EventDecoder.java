package io.aevumpay.chain;

import io.aevumpay.common.Eth;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;

/**
 * 把 eth_getLogs 的原始 log 解成领域事件。topic0 用 web3j 按规范签名现算，
 * 和 Solidity 事件声明必须一字不差，否则同步会静默漏单。
 */
public final class EventDecoder {

    private static final Event INVOICE_CREATED = event(
            "InvoiceCreated",
            List.of(
                    TypeReference.create(Uint256.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint64.class),
                    TypeReference.create(Bool.class),
                    TypeReference.create(Bytes32.class)));

    private static final Event INVOICE_PAID = event(
            "InvoicePaid",
            List.of(
                    TypeReference.create(Uint256.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Bool.class)));

    private static final Event INVOICE_CANCELLED = event(
            "InvoiceCancelled",
            List.of(TypeReference.create(Uint256.class, true), TypeReference.create(Address.class, true)));

    private static final Event INVOICE_REFUNDED = event(
            "InvoiceRefunded",
            List.of(
                    TypeReference.create(Uint256.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class)));

    private static final Map<String, Event> BY_TOPIC = Map.of(
            topic(INVOICE_CREATED), INVOICE_CREATED,
            topic(INVOICE_PAID), INVOICE_PAID,
            topic(INVOICE_CANCELLED), INVOICE_CANCELLED,
            topic(INVOICE_REFUNDED), INVOICE_REFUNDED);

    private EventDecoder() {}

    public static Optional<DecodedEvent> decode(Log log) {
        if (log == null || log.getTopics() == null || log.getTopics().isEmpty()) {
            return Optional.empty();
        }
        Event event = BY_TOPIC.get(log.getTopics().get(0).toLowerCase(Locale.ROOT));
        if (event == null) {
            return Optional.empty();
        }
        List<Type> indexed = new ArrayList<>();
        List<TypeReference<Type>> indexedTypes = event.getIndexedParameters();
        for (int i = 0; i < indexedTypes.size(); i++) {
            indexed.add(FunctionReturnDecoder.decodeIndexedValue(log.getTopics().get(i + 1), indexedTypes.get(i)));
        }
        List<Type> data = FunctionReturnDecoder.decode(log.getData(), event.getNonIndexedParameters());
        Map<String, Object> fields = new LinkedHashMap<>();
        long chainInvoiceId = ((Uint256) indexed.get(0)).getValue().longValueExact();
        switch (event.getName()) {
            case "InvoiceCreated" -> {
                fields.put("merchant", address(indexed.get(1)));
                fields.put("payer", address(indexed.get(2)));
                fields.put("amount", decimal(data.get(0)));
                fields.put("dueAt", ((Uint64) data.get(1)).getValue().toString());
                fields.put("allowPartial", ((Bool) data.get(2)).getValue());
                fields.put("orderRef", bytes32((Bytes32) data.get(3)));
            }
            case "InvoicePaid" -> {
                fields.put("payer", address(indexed.get(1)));
                fields.put("merchant", address(indexed.get(2)));
                fields.put("amount", decimal(data.get(0)));
                fields.put("fee", decimal(data.get(1)));
                fields.put("totalPaid", decimal(data.get(2)));
                fields.put("fullyPaid", ((Bool) data.get(3)).getValue());
            }
            case "InvoiceCancelled" -> fields.put("merchant", address(indexed.get(1)));
            case "InvoiceRefunded" -> {
                fields.put("to", address(indexed.get(1)));
                fields.put("amount", decimal(data.get(0)));
                fields.put("totalRefunded", decimal(data.get(1)));
            }
            default -> {
                return Optional.empty();
            }
        }
        int logIndex = log.getLogIndex() == null ? 0 : log.getLogIndex().intValue();
        long blockNumber = log.getBlockNumber() == null ? 0L : log.getBlockNumber().longValue();
        return Optional.of(new DecodedEvent(
                event.getName(),
                lower(log.getTransactionHash()),
                logIndex,
                blockNumber,
                lower(log.getBlockHash()),
                chainInvoiceId,
                fields));
    }

    public static String topic(Event event) {
        return EventEncoder.encode(event).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Event event(String name, List<TypeReference<?>> parameters) {
        return new Event(name, (List) parameters);
    }

    private static String address(Type value) {
        return ((Address) value).getValue().toLowerCase(Locale.ROOT);
    }

    private static String decimal(Type value) {
        return ((Uint256) value).getValue().toString();
    }

    private static String bytes32(Bytes32 value) {
        return Eth.hex(value.getValue());
    }

    private static String lower(String hex) {
        return hex == null ? null : hex.toLowerCase(Locale.ROOT);
    }

    public static BigInteger uint(String decimal) {
        return new BigInteger(decimal);
    }
}
