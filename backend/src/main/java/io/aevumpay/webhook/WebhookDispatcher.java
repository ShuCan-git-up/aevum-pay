package io.aevumpay.webhook;

import io.aevumpay.config.AevumProperties;
import io.aevumpay.merchant.MerchantRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookRepository webhooks;
    private final MerchantRepository merchants;
    private final AevumProperties properties;
    private final TransactionTemplate tx;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WebhookDispatcher(
            WebhookRepository webhooks,
            MerchantRepository merchants,
            AevumProperties properties,
            PlatformTransactionManager transactionManager) {
        this.webhooks = webhooks;
        this.merchants = merchants;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${aevum.webhook-dispatch-interval-ms:2000}")
    public void deliverDue() {
        List<WebhookOutbox> due =
                webhooks.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(WebhookStatus.PENDING, Instant.now());
        for (WebhookOutbox row : due) {
            deliver(row.getId());
        }
    }

    public void deliver(Long id) {
        WebhookOutbox row = webhooks.findById(id).orElse(null);
        if (row == null || row.getStatus() != WebhookStatus.PENDING) {
            return;
        }
        var merchant = merchants.findById(row.getMerchantId()).orElse(null);
        if (merchant == null || merchant.getWebhookUrl() == null || merchant.getWebhookUrl().isBlank()) {
            tx.executeWithoutResult(status -> mark(id, false, "merchant has no webhook url"));
            return;
        }
        String secret = merchant.getWebhookSecret() == null ? "" : merchant.getWebhookSecret();
        String header = HmacSigner.header(secret, row.getPayload(), Instant.now());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(merchant.getWebhookUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-AevumPay-Signature", header)
                    .header("User-Agent", "aevum-pay-webhook")
                    .POST(HttpRequest.BodyPublishers.ofString(row.getPayload()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            String error = ok ? null : "HTTP " + response.statusCode();
            tx.executeWithoutResult(status -> mark(id, ok, error));
        } catch (Exception exception) {
            log.info("webhook {} failed: {}", id, exception.toString());
            tx.executeWithoutResult(status -> mark(id, false, exception.getMessage()));
        }
    }

    private void mark(Long id, boolean ok, String error) {
        WebhookOutbox row = webhooks.findById(id).orElseThrow();
        if (ok) {
            row.setStatus(WebhookStatus.DELIVERED);
            row.setLastError(null);
            return;
        }
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        String message = error == null ? "delivery failed" : error;
        row.setLastError(message.length() > 500 ? message.substring(0, 500) : message);
        List<Integer> schedule = properties.retryScheduleSeconds();
        if (schedule.isEmpty() || attempts > schedule.size()) {
            row.setStatus(WebhookStatus.DEAD);
            return;
        }
        row.setNextAttemptAt(Instant.now().plusSeconds(schedule.get(attempts - 1)));
    }
}
