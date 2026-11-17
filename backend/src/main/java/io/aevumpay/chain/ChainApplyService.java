package io.aevumpay.chain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aevumpay.common.Eth;
import io.aevumpay.config.AevumProperties;
import io.aevumpay.invoice.InvoiceEntity;
import io.aevumpay.invoice.InvoiceRepository;
import io.aevumpay.invoice.InvoiceStatus;
import io.aevumpay.merchant.MerchantEntity;
import io.aevumpay.merchant.MerchantRepository;
import io.aevumpay.webhook.WebhookOutbox;
import io.aevumpay.webhook.WebhookRepository;
import io.aevumpay.webhook.WebhookStatus;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChainApplyService {

    private static final Logger log = LoggerFactory.getLogger(ChainApplyService.class);

    private final ChainEventRepository events;
    private final InvoiceRepository invoices;
    private final MerchantRepository merchants;
    private final WebhookRepository webhooks;
    private final SyncCursorRepository cursors;
    private final AevumProperties properties;
    private final ObjectMapper json;

    public ChainApplyService(
            ChainEventRepository events,
            InvoiceRepository invoices,
            MerchantRepository merchants,
            WebhookRepository webhooks,
            SyncCursorRepository cursors,
            AevumProperties properties,
            ObjectMapper json) {
        this.events = events;
        this.invoices = invoices;
        this.merchants = merchants;
        this.webhooks = webhooks;
        this.cursors = cursors;
        this.properties = properties;
        this.json = json;
    }

    @Transactional
    public void applyBatch(List<DecodedEvent> decoded, long cursorBlock, String cursorHash) {
        for (DecodedEvent event : decoded) {
            applyOne(event, true);
        }
        saveCursor(cursorBlock, cursorHash);
    }

    @Transactional
    public void rewind(long newLastBlock, String newHash) {
        List<ChainEventEntity> doomed = events.findByBlockNumberGreaterThan(newLastBlock);
        if (!doomed.isEmpty()) {
            List<Long> ids = doomed.stream().map(ChainEventEntity::getId).toList();
            Set<Long> invoiceIds = new LinkedHashSet<>();
            doomed.forEach(row -> invoiceIds.add(row.getChainInvoiceId()));
            boolean delivered = webhooks.existsByChainEventIdInAndStatus(ids, WebhookStatus.DELIVERED);
            webhooks.deleteByChainEventIdInAndStatus(ids, WebhookStatus.PENDING);
            events.deleteAll(doomed);
            events.flush();
            List<InvoiceEntity> touched = new ArrayList<>();
            for (Long chainId : invoiceIds) {
                touched.add(replay(chainId));
            }
            if (delivered) {
                for (InvoiceEntity invoice : touched) {
                    if (invoice != null) {
                        enqueue(invoice, "invoice.reorg", null, Map.of("reason", "reorg"));
                    }
                }
            }
            log.warn("reorg rewind to block {} dropped {} events", newLastBlock, doomed.size());
        }
        saveCursor(newLastBlock, newHash == null ? "" : newHash);
    }

    @Transactional
    public void applyOne(DecodedEvent event, boolean emitWebhook) {
        if (events.existsByTxHashAndLogIndex(event.txHash(), event.logIndex())) {
            return;
        }
        ChainEventEntity row = new ChainEventEntity();
        row.setTxHash(event.txHash());
        row.setLogIndex(event.logIndex());
        row.setBlockNumber(event.blockNumber());
        row.setBlockHash(event.blockHash() == null ? "" : event.blockHash());
        row.setEventName(event.eventName());
        row.setChainInvoiceId(event.chainInvoiceId());
        row.setPayload(write(event.fields()));
        events.save(row);
        InvoiceEntity invoice = switch (event.eventName()) {
            case "InvoiceCreated" -> onCreated(event);
            case "InvoicePaid" -> onPaid(event);
            case "InvoiceCancelled" -> onCancelled(event);
            case "InvoiceRefunded" -> onRefunded(event);
            default -> null;
        };
        if (emitWebhook && invoice != null) {
            Map<String, Object> extra = new LinkedHashMap<>(event.fields());
            extra.put("txHash", event.txHash());
            extra.put("blockNumber", event.blockNumber());
            extra.put("explorerUrl", explorerTx(event.txHash()));
            extra.put("payUrl", payUrl(invoice));
            enqueue(invoice, webhookType(event.eventName()), row.getId(), extra);
        }
    }

    private InvoiceEntity replay(long chainInvoiceId) {
        List<ChainEventEntity> rows = events.findByChainInvoiceIdOrderByBlockNumberAscLogIndexAsc(chainInvoiceId);
        InvoiceEntity existing = invoices.findByChainInvoiceId(chainInvoiceId).orElse(null);
        if (rows.isEmpty()) {
            if (existing != null && existing.getStatus() != InvoiceStatus.CREATING) {
                invoices.delete(existing);
            }
            return null;
        }
        if (existing != null) {
            existing.setPaidAmount(BigInteger.ZERO);
            existing.setRefundedAmount(BigInteger.ZERO);
            existing.setStatus(InvoiceStatus.PENDING);
        }
        InvoiceEntity current = existing;
        for (ChainEventEntity row : rows) {
            Map<String, Object> fields = read(row.getPayload());
            DecodedEvent event = new DecodedEvent(
                    row.getEventName(),
                    row.getTxHash(),
                    row.getLogIndex(),
                    row.getBlockNumber(),
                    row.getBlockHash(),
                    row.getChainInvoiceId(),
                    fields);
            current = switch (row.getEventName()) {
                case "InvoiceCreated" -> onCreated(event);
                case "InvoicePaid" -> onPaid(event);
                case "InvoiceCancelled" -> onCancelled(event);
                case "InvoiceRefunded" -> onRefunded(event);
                default -> current;
            };
        }
        return current;
    }

    private InvoiceEntity onCreated(DecodedEvent event) {
        Map<String, Object> fields = event.fields();
        String merchantAddr = String.valueOf(fields.get("merchant")).toLowerCase();
        String payer = String.valueOf(fields.get("payer")).toLowerCase();
        BigInteger amount = new BigInteger(String.valueOf(fields.get("amount")));
        long dueAt = Long.parseLong(String.valueOf(fields.get("dueAt")));
        boolean allowPartial = Boolean.TRUE.equals(fields.get("allowPartial"))
                || "true".equalsIgnoreCase(String.valueOf(fields.get("allowPartial")));
        String orderRef = String.valueOf(fields.get("orderRef")).toLowerCase();
        MerchantEntity merchant = merchant(merchantAddr);
        InvoiceEntity invoice = invoices.findByChainInvoiceId(event.chainInvoiceId()).orElse(null);
        if (invoice == null && event.txHash() != null) {
            invoice = invoices.findByCreateTxHash(event.txHash()).orElse(null);
        }
        if (invoice == null && !Eth.isZeroBytes32(orderRef)) {
            invoice = invoices.findByMerchantAndOrderRef(merchant, orderRef).orElse(null);
        }
        if (invoice == null) {
            invoice = new InvoiceEntity();
            invoice.setMerchant(merchant);
            invoice.setOrderId("");
            invoice.setPaidAmount(BigInteger.ZERO);
            invoice.setRefundedAmount(BigInteger.ZERO);
        }
        invoice.setMerchant(merchant);
        invoice.setChainInvoiceId(event.chainInvoiceId());
        invoice.setAmount(amount);
        invoice.setAllowPartial(allowPartial);
        invoice.setDueAt(Instant.ofEpochSecond(dueAt));
        if (!Eth.isZeroBytes32(orderRef)) {
            invoice.setOrderRef(orderRef);
        }
        if (!Eth.isZeroAddress(payer)) {
            invoice.setPayerAddress(payer);
        }
        if (invoice.getCreateTxHash() == null) {
            invoice.setCreateTxHash(event.txHash());
        }
        if (invoice.getStatus() == null
                || invoice.getStatus() == InvoiceStatus.CREATING
                || invoice.getStatus() == InvoiceStatus.FAILED) {
            invoice.setStatus(InvoiceStatus.PENDING);
        }
        return invoices.save(invoice);
    }

    private InvoiceEntity onPaid(DecodedEvent event) {
        InvoiceEntity invoice = ensure(event);
        Map<String, Object> fields = event.fields();
        invoice.setPaidAmount(new BigInteger(String.valueOf(fields.get("totalPaid"))));
        String payer = String.valueOf(fields.get("payer")).toLowerCase();
        if (!Eth.isZeroAddress(payer)) {
            invoice.setPayerAddress(payer);
        }
        boolean fully = Boolean.TRUE.equals(fields.get("fullyPaid"))
                || "true".equalsIgnoreCase(String.valueOf(fields.get("fullyPaid")));
        invoice.setStatus(fully ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID);
        invoice.setLastTxHash(event.txHash());
        return invoices.save(invoice);
    }

    private InvoiceEntity onCancelled(DecodedEvent event) {
        InvoiceEntity invoice = ensure(event);
        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoice.setLastTxHash(event.txHash());
        return invoices.save(invoice);
    }

    private InvoiceEntity onRefunded(DecodedEvent event) {
        InvoiceEntity invoice = ensure(event);
        BigInteger total = new BigInteger(String.valueOf(event.fields().get("totalRefunded")));
        invoice.setRefundedAmount(total);
        if (invoice.getPaidAmount() != null
                && invoice.getPaidAmount().signum() > 0
                && total.compareTo(invoice.getPaidAmount()) >= 0) {
            invoice.setStatus(InvoiceStatus.REFUNDED);
        }
        invoice.setLastTxHash(event.txHash());
        return invoices.save(invoice);
    }

    private InvoiceEntity ensure(DecodedEvent event) {
        return invoices.findByChainInvoiceId(event.chainInvoiceId()).orElseGet(() -> {
            InvoiceEntity created = new InvoiceEntity();
            String merchantAddr = event.fields().get("merchant") == null
                    ? null
                    : String.valueOf(event.fields().get("merchant")).toLowerCase();
            if (merchantAddr == null || !merchantAddr.startsWith("0x")) {
                merchantAddr = Eth.ZERO_ADDRESS;
            }
            created.setMerchant(merchant(merchantAddr));
            created.setChainInvoiceId(event.chainInvoiceId());
            created.setOrderId("");
            created.setAmount(BigInteger.ZERO);
            created.setPaidAmount(BigInteger.ZERO);
            created.setRefundedAmount(BigInteger.ZERO);
            created.setDueAt(Instant.EPOCH);
            created.setStatus(InvoiceStatus.PENDING);
            created.setAllowPartial(true);
            return invoices.save(created);
        });
    }

    private MerchantEntity merchant(String address) {
        String normalized = Eth.normalizeAddress(address);
        return merchants.findByWalletAddress(normalized).orElseGet(() -> {
            MerchantEntity created = new MerchantEntity();
            created.setWalletAddress(normalized);
            created.setName("Merchant " + normalized.substring(0, 8));
            return merchants.save(created);
        });
    }

    private void enqueue(InvoiceEntity invoice, String type, Long chainEventId, Map<String, Object> extra) {
        MerchantEntity merchant = invoice.getMerchant();
        if (merchant.getWebhookUrl() == null || merchant.getWebhookUrl().isBlank()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", chainEventId == null ? "reorg-" + invoice.getChainInvoiceId() : "evt_" + chainEventId);
        body.put("type", type);
        body.put("invoiceId", invoice.getChainInvoiceId());
        body.put("localId", invoice.getId());
        body.put("orderId", invoice.getOrderId());
        body.put("merchant", merchant.getWalletAddress());
        body.put("payer", invoice.getPayerAddress());
        body.put("invoiceAmount", text(invoice.getAmount()));
        body.put("totalPaid", text(invoice.getPaidAmount()));
        body.put("totalRefunded", text(invoice.getRefundedAmount()));
        body.put("status", invoice.getStatus().name());
        if (extra != null) {
            body.putAll(extra);
        }
        body.putIfAbsent("amount", text(invoice.getAmount()));
        body.put("occurredAt", Instant.now().toString());
        WebhookOutbox row = new WebhookOutbox();
        row.setMerchantId(merchant.getId());
        row.setChainEventId(chainEventId);
        row.setPayload(write(body));
        row.setStatus(WebhookStatus.PENDING);
        row.setNextAttemptAt(Instant.now());
        webhooks.save(row);
    }

    private void saveCursor(long block, String hash) {
        SyncCursorEntity cursor = cursors.findById(SyncCursorEntity.NAME).orElseGet(SyncCursorEntity::new);
        cursor.setName(SyncCursorEntity.NAME);
        cursor.setLastBlock(block);
        cursor.setLastBlockHash(hash == null ? "" : hash);
        cursor.setUpdatedAt(Instant.now());
        cursors.save(cursor);
    }

    private String write(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> read(String payload) {
        try {
            return json.readValue(payload, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String text(BigInteger value) {
        return value == null ? "0" : value.toString();
    }

    private String payUrl(InvoiceEntity invoice) {
        if (invoice.getChainInvoiceId() == null) {
            return null;
        }
        String base = properties.getPublicBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/pay/" + invoice.getChainInvoiceId();
    }

    private String explorerTx(String txHash) {
        if (txHash == null || properties.getExplorerUrl() == null || properties.getExplorerUrl().isBlank()) {
            return null;
        }
        String base = properties.getExplorerUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/tx/" + txHash;
    }

    private static String webhookType(String eventName) {
        return switch (eventName) {
            case "InvoiceCreated" -> "invoice.created";
            case "InvoicePaid" -> "invoice.paid";
            case "InvoiceCancelled" -> "invoice.cancelled";
            case "InvoiceRefunded" -> "invoice.refunded";
            default -> "invoice.updated";
        };
    }
}
