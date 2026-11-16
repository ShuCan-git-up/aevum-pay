package io.aevumpay.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;
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

class EventDecoderTest {

    @Test
    void decodesInvoiceCreatedAndPaid() {
        String merchant = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
        String payer = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc";
        byte[] orderRef = Numeric.hexStringToByteArray("0x" + "ab".repeat(32));

        Event created = new Event(
                "InvoiceCreated",
                List.of(
                        TypeReference.create(Uint256.class, true),
                        TypeReference.create(Address.class, true),
                        TypeReference.create(Address.class, true),
                        TypeReference.create(Uint256.class),
                        TypeReference.create(Uint64.class),
                        TypeReference.create(Bool.class),
                        TypeReference.create(Bytes32.class)));
        String data = FunctionEncoder.encodeConstructor(List.<Type>of(
                new Uint256(BigInteger.valueOf(1_500_000)),
                new Uint64(1_800_000_000L),
                new Bool(true),
                new Bytes32(orderRef)));
        Log log = log(
                EventEncoder.encode(created),
                List.of(topic(BigInteger.valueOf(7)), topicAddress(merchant), topicAddress(payer)),
                data,
                "0x11",
                4);
        DecodedEvent event = EventDecoder.decode(log).orElseThrow();
        assertEquals("InvoiceCreated", event.eventName());
        assertEquals(7L, event.chainInvoiceId());
        assertEquals(merchant, event.fields().get("merchant"));
        assertEquals(payer, event.fields().get("payer"));
        assertEquals("1500000", event.fields().get("amount"));
        assertEquals("1800000000", event.fields().get("dueAt"));
        assertEquals(Boolean.TRUE, event.fields().get("allowPartial"));
        assertTrue(event.fields().get("orderRef").toString().endsWith("ab".repeat(32)));

        Event paid = new Event(
                "InvoicePaid",
                List.of(
                        TypeReference.create(Uint256.class, true),
                        TypeReference.create(Address.class, true),
                        TypeReference.create(Address.class, true),
                        TypeReference.create(Uint256.class),
                        TypeReference.create(Uint256.class),
                        TypeReference.create(Uint256.class),
                        TypeReference.create(Bool.class)));
        String paidData = FunctionEncoder.encodeConstructor(List.<Type>of(
                new Uint256(BigInteger.valueOf(1_500_000)),
                new Uint256(BigInteger.ZERO),
                new Uint256(BigInteger.valueOf(1_500_000)),
                new Bool(true)));
        Log paidLog = log(
                EventEncoder.encode(paid),
                List.of(topic(BigInteger.valueOf(7)), topicAddress(payer), topicAddress(merchant)),
                paidData,
                "0x22",
                5);
        DecodedEvent paidEvent = EventDecoder.decode(paidLog).orElseThrow();
        assertEquals("InvoicePaid", paidEvent.eventName());
        assertEquals("1500000", paidEvent.fields().get("totalPaid"));
        assertEquals(Boolean.TRUE, paidEvent.fields().get("fullyPaid"));
        assertEquals(payer, paidEvent.fields().get("payer"));
    }

    private static Log log(String topic0, List<String> rest, String data, String tx, int index) {
        Log log = new Log();
        log.setRemoved(false);
        java.util.ArrayList<String> topics = new java.util.ArrayList<>();
        topics.add(topic0);
        topics.addAll(rest);
        log.setTopics(topics);
        log.setData(data);
        log.setTransactionHash(tx);
        log.setBlockHash("0x" + "cd".repeat(32));
        log.setBlockNumber("0x10");
        log.setLogIndex("0x" + Integer.toHexString(index));
        log.setAddress("0x0000000000000000000000000000000000000001");
        return log;
    }

    private static String topic(BigInteger value) {
        return Numeric.toHexStringWithPrefixZeroPadded(value, 64);
    }

    private static String topicAddress(String address) {
        return topic(Numeric.toBigInt(address));
    }
}
