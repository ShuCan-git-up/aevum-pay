package io.aevumpay.invoice;

import io.aevumpay.chain.ChainWriter;
import io.aevumpay.chain.InvoiceReader;
import io.aevumpay.chain.OnChainInvoice;
import io.aevumpay.chain.SendResult;
import io.aevumpay.common.ApiException;
import io.aevumpay.common.Eth;
import io.aevumpay.config.AevumProperties;
import io.aevumpay.merchant.MerchantEntity;
import io.aevumpay.merchant.MerchantRepository;
import io.aevumpay.reconcile.ReconciliationJob;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvoiceService {

    private static final BigInteger UINT128_MAX = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);

    private final InvoiceRepository invoices;
    private final IdempotencyRepository idempotency;
    private final MerchantRepository merchants;
    private final ChainWriter chainWriter;
    private final InvoiceReader reader;
    private final AevumProperties properties;
    private final TransactionTemplate tx;

    public InvoiceService(
            InvoiceRepository invoices,
            IdempotencyRepository idempotency,
            MerchantRepository merchants,
            ChainWriter chainWriter,
            InvoiceReader reader,
            AevumProperties properties,
            PlatformTransactionManager transactionManager) {
        this.invoices = invoices;
        this.idempotency = idempotency;
        this.merchants = merchants;
        this.chainWriter = chainWriter;
        this.reader = reader;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public InvoiceView create(Long merchantId, CreateInvoiceRequest request, String idempotencyKey) {
        String key = idempotencyKey == null ? null : idempotencyKey.trim();
        if (key != null && !key.isEmpty()) {
            var existing = idempotency.findByMerchantIdAndIdemKey(merchantId, key);
            if (existing.isPresent()) {
                Long existingId = existing.get().getInvoiceId();
                return tx.execute(status -> view(load(existingId)));
            }
        }
        Parsed parsed = parse(request);
        Long localId = tx.execute(status -> insert(merchantId, request, parsed, key));
        String merchantAddress = merchants.findById(merchantId).orElseThrow(() -> ApiException.notFound("merchant")).getWalletAddress();
        try {
            SendResult sent = chainWriter.createInvoiceFor(
                    merchantAddress, parsed.payer(), parsed.amount(), parsed.dueAtEpoch(), parsed.allowPartial(), parsed.orderRef());
            return tx.execute(status -> {
                InvoiceEntity invoice = load(localId);
                invoice.setCreateTxHash(sent.txHash());
                if (sent.chainInvoiceId() != null) {
                    invoice.setChainInvoiceId(sent.chainInvoiceId());
                }
                return view(invoice);
            });
        } catch (RuntimeException exception) {
            tx.executeWithoutResult(status -> {
                InvoiceEntity invoice = invoices.findById(localId).orElse(null);
                if (invoice != null && invoice.getStatus() == InvoiceStatus.CREATING) {
                    invoice.setStatus(InvoiceStatus.FAILED);
                    String message = exception.getMessage() == null ? "send failed" : exception.getMessage();
                    invoice.setFailureReason(message.length() > 300 ? message.substring(0, 300) : message);
                }
            });
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public InvoiceView getOwned(Long merchantId, Long localId) {
        InvoiceEntity invoice = load(localId);
        if (!invoice.getMerchant().getId().equals(merchantId)) {
            throw ApiException.notFound("invoice");
        }
        return view(invoice);
    }

    @Transactional(readOnly = true)
    public List<InvoiceView> listOwned(Long merchantId, String status, Instant from, Instant to) {
        MerchantEntity merchant = merchants.findById(merchantId).orElseThrow(() -> ApiException.notFound("merchant"));
        return invoices.findByMerchantOrderByIdDesc(merchant).stream()
                .filter(invoice -> status == null || status.isBlank() || invoice.getStatus().name().equalsIgnoreCase(status))
                .filter(invoice -> from == null || !invoice.getCreatedAt().isBefore(from))
                .filter(invoice -> to == null || !invoice.getCreatedAt().isAfter(to))
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public String exportCsv(Long merchantId) {
        StringBuilder csv = new StringBuilder("localId,chainInvoiceId,orderId,status,amount,paidAmount,refundedAmount,dueAt,createTxHash,lastTxHash\n");
        for (InvoiceView row : listOwned(merchantId, null, null, null)) {
            csv.append(row.localId()).append(',')
                    .append(row.chainInvoiceId() == null ? "" : row.chainInvoiceId()).append(',')
                    .append(escape(row.orderId())).append(',')
                    .append(row.status()).append(',')
                    .append(row.amount()).append(',')
                    .append(row.paidAmount()).append(',')
                    .append(row.refundedAmount()).append(',')
                    .append(row.dueAt()).append(',')
                    .append(row.createTxHash() == null ? "" : row.createTxHash()).append(',')
                    .append(row.lastTxHash() == null ? "" : row.lastTxHash())
                    .append('\n');
        }
        return csv.toString();
    }

    public InvoiceView cancel(Long merchantId, Long localId) {
        CancelGate gate = tx.execute(status -> {
            InvoiceEntity invoice = load(localId);
            if (!invoice.getMerchant().getId().equals(merchantId)) {
                throw ApiException.notFound("invoice");
            }
            return new CancelGate(invoice.getChainInvoiceId(), invoice.getPaidAmount(), invoice.getStatus());
        });
        if (gate.chainInvoiceId() == null) {
            throw ApiException.badRequest("not_on_chain", "invoice has no chain id yet");
        }
        if (gate.paidAmount().signum() > 0 || gate.status() == InvoiceStatus.CANCELLED) {
            throw ApiException.conflict("not_cancellable", "only unpaid invoices can be cancelled");
        }
        SendResult sent = chainWriter.cancel(gate.chainInvoiceId());
        return tx.execute(status -> {
            InvoiceEntity current = load(localId);
            current.setLastTxHash(sent.txHash());
            current.setStatus(InvoiceStatus.CANCELLED);
            return view(current);
        });
    }

    @Transactional(readOnly = true)
    public List<InvoiceView> listPublic(String merchantAddress) {
        String address = Eth.normalizeAddress(merchantAddress);
        return invoices.findByMerchant_WalletAddressOrderByIdDesc(address).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public InvoiceView publicByChainId(long chainInvoiceId) {
        InvoiceEntity entity = invoices.findByChainInvoiceId(chainInvoiceId).orElse(null);
        OnChainInvoice chain = null;
        if (properties.contractConfigured()) {
            try {
                chain = reader.read(chainInvoiceId);
            } catch (Exception ignored) {
                chain = null;
            }
        }
        if (entity == null && chain == null) {
            throw ApiException.notFound("invoice");
        }
        if (entity == null) {
            return fromChain(chainInvoiceId, chain);
        }
        if (chain == null) {
            return view(entity);
        }
        InvoiceView base = view(entity);
        InvoiceStatus status = ReconciliationJob.map(chain.status());
        BigInteger remaining = chain.amount().subtract(chain.paidAmount());
        if (remaining.signum() < 0) {
            remaining = BigInteger.ZERO;
        }
        return new InvoiceView(
                base.localId(),
                chainInvoiceId,
                base.orderId(),
                chain.orderRef(),
                chain.merchant(),
                Eth.isZeroAddress(chain.payer()) ? null : chain.payer(),
                chain.amount().toString(),
                chain.paidAmount().toString(),
                chain.refundedAmount().toString(),
                remaining.toString(),
                chain.allowPartial(),
                Instant.ofEpochSecond(chain.dueAt()),
                status.name(),
                base.createTxHash(),
                base.lastTxHash(),
                payUrl(chainInvoiceId),
                base.explorerTxUrl(),
                base.updatedAt());
    }

    private Long insert(Long merchantId, CreateInvoiceRequest request, Parsed parsed, String idempotencyKey) {
        MerchantEntity merchant = merchants.findById(merchantId).orElseThrow(() -> ApiException.notFound("merchant"));
        InvoiceEntity invoice = null;
        if (parsed.storedOrderRef() != null) {
            invoice = invoices.findByMerchantAndOrderRef(merchant, parsed.storedOrderRef()).orElse(null);
            if (invoice != null && invoice.getStatus() != InvoiceStatus.FAILED) {
                throw ApiException.conflict("duplicate_order", "orderId already used for this merchant");
            }
        }
        if (invoice == null) {
            invoice = new InvoiceEntity();
            invoice.setMerchant(merchant);
        }
        invoice.setOrderId(request.orderId() == null ? "" : request.orderId().trim());
        invoice.setOrderRef(parsed.storedOrderRef());
        invoice.setPayerAddress(parsed.payer());
        invoice.setAmount(parsed.amount());
        invoice.setPaidAmount(BigInteger.ZERO);
        invoice.setRefundedAmount(BigInteger.ZERO);
        invoice.setAllowPartial(parsed.allowPartial());
        invoice.setDueAt(Instant.ofEpochSecond(parsed.dueAtEpoch()));
        invoice.setStatus(InvoiceStatus.CREATING);
        invoice.setFailureReason(null);
        invoice.setChainInvoiceId(null);
        invoices.save(invoice);
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            IdempotencyRecord record = new IdempotencyRecord();
            record.setMerchantId(merchantId);
            record.setIdemKey(idempotencyKey);
            record.setInvoiceId(invoice.getId());
            idempotency.save(record);
        }
        return invoice.getId();
    }

    private Parsed parse(CreateInvoiceRequest request) {
        if (request == null || request.amount() == null || request.dueAt() == null) {
            throw ApiException.badRequest("bad_invoice", "amount and dueAt are required");
        }
        BigInteger amount;
        try {
            amount = new BigInteger(request.amount().trim());
        } catch (NumberFormatException exception) {
            throw ApiException.badRequest("bad_amount", "amount must be an integer in token base units");
        }
        if (amount.signum() <= 0 || amount.compareTo(UINT128_MAX) > 0) {
            throw ApiException.badRequest("bad_amount", "amount must be positive and fit in uint128");
        }
        long dueAt = request.dueAt().getEpochSecond();
        long now = Instant.now().getEpochSecond();
        if (dueAt <= now || request.dueAt().isAfter(Instant.now().plus(3650, ChronoUnit.DAYS))) {
            throw ApiException.badRequest("bad_due", "dueAt must be in the future and within 10 years");
        }
        String payer = null;
        if (request.payer() != null && !request.payer().isBlank()) {
            payer = Eth.normalizeAddress(request.payer());
            if (Eth.isZeroAddress(payer)) {
                payer = null;
            }
        }
        if (request.orderId() != null && request.orderId().length() > 100) {
            throw ApiException.badRequest("bad_order", "orderId is too long");
        }
        String orderRef = Eth.orderRef(request.orderId());
        return new Parsed(amount, dueAt, request.allowPartial(), payer, orderRef, Eth.isZeroBytes32(orderRef) ? null : orderRef);
    }

    private InvoiceEntity load(Long id) {
        return invoices.findById(id).orElseThrow(() -> ApiException.notFound("invoice"));
    }

    public InvoiceView view(InvoiceEntity invoice) {
        BigInteger paid = invoice.getPaidAmount() == null ? BigInteger.ZERO : invoice.getPaidAmount();
        BigInteger amount = invoice.getAmount() == null ? BigInteger.ZERO : invoice.getAmount();
        BigInteger remaining = amount.subtract(paid);
        if (remaining.signum() < 0) {
            remaining = BigInteger.ZERO;
        }
        String tx = invoice.getLastTxHash() != null ? invoice.getLastTxHash() : invoice.getCreateTxHash();
        return new InvoiceView(
                invoice.getId(),
                invoice.getChainInvoiceId(),
                invoice.getOrderId(),
                invoice.getOrderRef(),
                invoice.getMerchant().getWalletAddress(),
                invoice.getPayerAddress(),
                amount.toString(),
                paid.toString(),
                invoice.getRefundedAmount() == null ? "0" : invoice.getRefundedAmount().toString(),
                remaining.toString(),
                invoice.isAllowPartial(),
                invoice.getDueAt(),
                displayStatus(invoice).name(),
                invoice.getCreateTxHash(),
                invoice.getLastTxHash(),
                invoice.getChainInvoiceId() == null ? null : payUrl(invoice.getChainInvoiceId()),
                explorer(tx),
                invoice.getUpdatedAt());
    }

    private InvoiceView fromChain(long chainInvoiceId, OnChainInvoice chain) {
        BigInteger remaining = chain.amount().subtract(chain.paidAmount());
        if (remaining.signum() < 0) {
            remaining = BigInteger.ZERO;
        }
        return new InvoiceView(
                null,
                chainInvoiceId,
                "",
                chain.orderRef(),
                chain.merchant(),
                Eth.isZeroAddress(chain.payer()) ? null : chain.payer(),
                chain.amount().toString(),
                chain.paidAmount().toString(),
                chain.refundedAmount().toString(),
                remaining.toString(),
                chain.allowPartial(),
                Instant.ofEpochSecond(chain.dueAt()),
                ReconciliationJob.map(chain.status()).name(),
                null,
                null,
                payUrl(chainInvoiceId),
                null,
                Instant.ofEpochSecond(chain.createdAt()));
    }

    private InvoiceStatus displayStatus(InvoiceEntity invoice) {
        if ((invoice.getStatus() == InvoiceStatus.PENDING || invoice.getStatus() == InvoiceStatus.PARTIALLY_PAID)
                && invoice.getDueAt() != null
                && invoice.getDueAt().isBefore(Instant.now())) {
            return InvoiceStatus.EXPIRED;
        }
        return invoice.getStatus();
    }

    private String payUrl(long chainInvoiceId) {
        String base = properties.getPublicBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/pay/" + chainInvoiceId;
    }

    private String explorer(String txHash) {
        if (txHash == null || properties.getExplorerUrl() == null || properties.getExplorerUrl().isBlank()) {
            return null;
        }
        String base = properties.getExplorerUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/tx/" + txHash;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record CancelGate(Long chainInvoiceId, BigInteger paidAmount, InvoiceStatus status) {}

    private record Parsed(
            BigInteger amount,
            long dueAtEpoch,
            boolean allowPartial,
            String payer,
            String orderRef,
            String storedOrderRef) {}
}
