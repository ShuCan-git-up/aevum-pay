package io.aevumpay.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.aevumpay.chain.ChainApplyService;
import io.aevumpay.chain.ChainWriter;
import io.aevumpay.chain.DecodedEvent;
import io.aevumpay.chain.SendResult;
import io.aevumpay.common.Eth;
import io.aevumpay.merchant.MerchantRepository;
import io.aevumpay.webhook.HmacSigner;
import io.aevumpay.webhook.WebhookDispatcher;
import io.aevumpay.webhook.WebhookRepository;
import io.aevumpay.webhook.WebhookStatus;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Sign;

@SpringBootTest
@AutoConfigureMockMvc
class InvoiceFlowTest {

    private static final String MERCHANT_KEY = "59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ChainApplyService applyService;

    @Autowired
    WebhookDispatcher dispatcher;

    @Autowired
    WebhookRepository webhooks;

    @Autowired
    MerchantRepository merchants;

    @Autowired
    InvoiceRepository invoices;

    @MockitoBean
    ChainWriter chainWriter;

    @Test
    void createIsIdempotentThenPaidEventDeliversSignedWebhook() throws Exception {
        Credentials merchant = Credentials.create(MERCHANT_KEY);
        String address = merchant.getAddress().toLowerCase();
        MvcResult nonce = mvc.perform(get("/api/v1/auth/nonce").param("address", address))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode nonceBody = json.readTree(nonce.getResponse().getContentAsString());
        String message = nonceBody.get("message").asText();
        Sign.SignatureData signature = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), merchant.getEcKeyPair());
        String signatureHex = signatureHex(signature);

        AtomicReference<String> webhookBody = new AtomicReference<>();
        AtomicReference<String> webhookSig = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            webhookBody.set(new String(raw, StandardCharsets.UTF_8));
            webhookSig.set(exchange.getRequestHeaders().getFirst("X-AevumPay-Signature"));
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
        try {
            String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            String register = json.writeValueAsString(Map.of(
                    "name", "Demo Shop",
                    "walletAddress", address,
                    "nonce", nonceBody.get("nonce").asText(),
                    "signature", signatureHex,
                    "webhookUrl", webhookUrl));
            MvcResult registered = mvc.perform(post("/api/v1/merchants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(register))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.apiKey").isNotEmpty())
                    .andReturn();
            String apiKey = json.readTree(registered.getResponse().getContentAsString()).get("apiKey").asText();
            String secret = json.readTree(registered.getResponse().getContentAsString()).get("webhookSecret").asText();

            when(chainWriter.createInvoiceFor(any(), any(), any(), anyLong(), anyBoolean(), any()))
                    .thenReturn(new SendResult("0xabc", 7L, true, null));

            String body = json.writeValueAsString(Map.of(
                    "orderId", "ORD-1",
                    "amount", "2500000",
                    "dueAt", Instant.now().plus(2, ChronoUnit.DAYS).toString(),
                    "allowPartial", false));
            mvc.perform(post("/api/v1/invoices")
                            .header("X-Api-Key", apiKey)
                            .header("Idempotency-Key", "idem-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.chainInvoiceId").value(7))
                    .andExpect(jsonPath("$.status").value("CREATING"));
            mvc.perform(post("/api/v1/invoices")
                            .header("X-Api-Key", apiKey)
                            .header("Idempotency-Key", "idem-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.chainInvoiceId").value(7));
            verify(chainWriter, times(1)).createInvoiceFor(any(), any(), any(), anyLong(), anyBoolean(), any());

            String orderRef = Eth.orderRef("ORD-1");
            applyService.applyOne(
                    new DecodedEvent(
                            "InvoiceCreated",
                            "0xabc",
                            0,
                            10,
                            "0x" + "11".repeat(32),
                            7,
                            Map.of(
                                    "merchant", address,
                                    "payer", Eth.ZERO_ADDRESS,
                                    "amount", "2500000",
                                    "dueAt", String.valueOf(Instant.now().plus(2, ChronoUnit.DAYS).getEpochSecond()),
                                    "allowPartial", false,
                                    "orderRef", orderRef)),
                    true);
            applyService.applyOne(
                    new DecodedEvent(
                            "InvoicePaid",
                            "0xdef",
                            1,
                            11,
                            "0x" + "22".repeat(32),
                            7,
                            Map.of(
                                    "payer", "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc",
                                    "merchant", address,
                                    "amount", "2500000",
                                    "fee", "0",
                                    "totalPaid", "2500000",
                                    "fullyPaid", true)),
                    true);

            mvc.perform(get("/api/v1/public/invoices/7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PAID"))
                    .andExpect(jsonPath("$.paidAmount").value("2500000"))
                    .andExpect(jsonPath("$.orderId").value("ORD-1"));

            dispatcher.deliverDue();
            assertTrue(webhookBody.get() != null && webhookBody.get().contains("invoice.paid"));
            assertTrue(webhookSig.get().startsWith("t="));
            String timestamp = webhookSig.get().substring(2, webhookSig.get().indexOf(','));
            String v1 = webhookSig.get().substring(webhookSig.get().indexOf("v1=") + 3);
            assertEquals(HmacSigner.hmac(secret, timestamp + "." + webhookBody.get()), v1);
            assertTrue(webhooks.findAll().stream().anyMatch(row -> row.getStatus() == WebhookStatus.DELIVERED));
            assertEquals(address, merchants.findByWalletAddress(address).orElseThrow().getWalletAddress());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void duplicatePaidEventDoesNotChangeTotals() {
        String merchant = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
        applyService.applyOne(created(merchant, 4, "0xaaa"), false);
        DecodedEvent paid = paid(merchant, 4, "0xbbb", "4", false);
        applyService.applyOne(paid, false);
        applyService.applyOne(paid, false);
        InvoiceEntity invoice = invoices.findByChainInvoiceId(4L).orElseThrow();
        assertEquals(new BigInteger("4"), invoice.getPaidAmount());
        assertEquals(InvoiceStatus.PARTIALLY_PAID, invoice.getStatus());
    }

    private static DecodedEvent created(String merchant, long id, String tx) {
        return new DecodedEvent(
                "InvoiceCreated",
                tx,
                0,
                3,
                "0x" + "33".repeat(32),
                id,
                Map.of(
                        "merchant", merchant,
                        "payer", Eth.ZERO_ADDRESS,
                        "amount", "10",
                        "dueAt", "2000000000",
                        "allowPartial", true,
                        "orderRef", Eth.ZERO_BYTES32));
    }

    private static DecodedEvent paid(String merchant, long id, String tx, String total, boolean fully) {
        return new DecodedEvent(
                "InvoicePaid",
                tx,
                1,
                4,
                "0x" + "44".repeat(32),
                id,
                Map.of(
                        "payer", "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc",
                        "merchant", merchant,
                        "amount", total,
                        "fee", "0",
                        "totalPaid", total,
                        "fullyPaid", fully));
    }

    private static String signatureHex(Sign.SignatureData signature) {
        byte[] r = signature.getR();
        byte[] s = signature.getS();
        byte[] v = signature.getV();
        byte[] out = new byte[65];
        System.arraycopy(r, 0, out, 0, 32);
        System.arraycopy(s, 0, out, 32, 32);
        out[64] = v[0];
        return io.aevumpay.common.Eth.hex(out);
    }
}
