package io.aevumpay.invoice;

import io.aevumpay.config.ApiKeyFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvoiceController {

    private final InvoiceService invoices;

    public InvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
    }

    @PostMapping("/api/v1/invoices")
    public ResponseEntity<InvoiceView> create(
            @RequestBody CreateInvoiceRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest http) {
        InvoiceView view = invoices.create(merchantId(http), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    @GetMapping("/api/v1/invoices")
    public List<InvoiceView> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            HttpServletRequest http) {
        return invoices.listOwned(merchantId(http), status, from, to);
    }

    @GetMapping("/api/v1/invoices/export")
    public ResponseEntity<String> export(HttpServletRequest http) {
        String csv = invoices.exportCsv(merchantId(http));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=invoices.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    @GetMapping("/api/v1/invoices/{id}")
    public InvoiceView get(@PathVariable Long id, HttpServletRequest http) {
        return invoices.getOwned(merchantId(http), id);
    }

    @PostMapping("/api/v1/invoices/{id}/cancel")
    public InvoiceView cancel(@PathVariable Long id, HttpServletRequest http) {
        return invoices.cancel(merchantId(http), id);
    }

    @GetMapping("/api/v1/invoices/{id}/receipt")
    public Map<String, Object> receipt(@PathVariable Long id, HttpServletRequest http) {
        InvoiceView view = invoices.getOwned(merchantId(http), id);
        return Map.of(
                "invoice", view,
                "note", "Compare amount, merchant and txHash with the Aevum explorer. Chain state is the source of truth.");
    }

    private static Long merchantId(HttpServletRequest request) {
        return (Long) request.getAttribute(ApiKeyFilter.MERCHANT_ATTR);
    }
}
