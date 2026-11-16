package io.aevumpay.reconcile;

import io.aevumpay.chain.InvoiceReader;
import io.aevumpay.chain.OnChainInvoice;
import io.aevumpay.config.AevumProperties;
import io.aevumpay.invoice.InvoiceEntity;
import io.aevumpay.invoice.InvoiceRepository;
import io.aevumpay.invoice.InvoiceStatus;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final AevumProperties properties;
    private final InvoiceRepository invoices;
    private final InvoiceReader reader;
    private final ReconciliationJob self;

    public ReconciliationJob(
            AevumProperties properties, InvoiceRepository invoices, InvoiceReader reader, @org.springframework.context.annotation.Lazy ReconciliationJob self) {
        this.properties = properties;
        this.invoices = invoices;
        this.reader = reader;
        this.self = self;
    }

    @Scheduled(fixedDelayString = "${aevum.reconcile-interval-ms:600000}")
    public void run() {
        if (!properties.contractConfigured()) {
            return;
        }
        List<InvoiceEntity> rows = invoices.findByStatusIn(List.of(
                InvoiceStatus.CREATING,
                InvoiceStatus.PENDING,
                InvoiceStatus.PARTIALLY_PAID,
                InvoiceStatus.PAID,
                InvoiceStatus.EXPIRED));
        for (InvoiceEntity row : rows) {
            if (row.getChainInvoiceId() == null) {
                continue;
            }
            try {
                OnChainInvoice chain = reader.read(row.getChainInvoiceId());
                self.apply(row.getId(), chain);
            } catch (Exception exception) {
                log.info("reconcile skipped invoice {}: {}", row.getChainInvoiceId(), exception.toString());
            }
        }
    }

    @Transactional
    public void apply(Long localId, OnChainInvoice chain) {
        InvoiceEntity invoice = invoices.findById(localId).orElse(null);
        if (invoice == null) {
            return;
        }
        InvoiceStatus status = map(chain.status());
        boolean changed = invoice.getStatus() != status
                || invoice.getPaidAmount().compareTo(chain.paidAmount()) != 0
                || invoice.getRefundedAmount().compareTo(chain.refundedAmount()) != 0;
        if (!changed) {
            return;
        }
        invoice.setAmount(chain.amount());
        invoice.setPaidAmount(chain.paidAmount());
        invoice.setRefundedAmount(chain.refundedAmount());
        invoice.setAllowPartial(chain.allowPartial());
        invoice.setDueAt(Instant.ofEpochSecond(chain.dueAt()));
        invoice.setStatus(status);
        if (chain.payer() != null && !chain.payer().endsWith("0000000000000000000000000000000000000000")) {
            invoice.setPayerAddress(chain.payer());
        }
        log.info("reconciled invoice {} -> {}", invoice.getChainInvoiceId(), status);
    }

    public static InvoiceStatus map(int status) {
        return switch (status) {
            case 1 -> InvoiceStatus.PENDING;
            case 2 -> InvoiceStatus.PARTIALLY_PAID;
            case 3 -> InvoiceStatus.PAID;
            case 4 -> InvoiceStatus.CANCELLED;
            case 5 -> InvoiceStatus.REFUNDED;
            case 6 -> InvoiceStatus.EXPIRED;
            default -> InvoiceStatus.PENDING;
        };
    }
}
