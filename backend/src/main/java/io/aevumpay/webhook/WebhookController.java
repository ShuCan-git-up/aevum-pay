package io.aevumpay.webhook;

import io.aevumpay.common.ApiException;
import io.aevumpay.config.ApiKeyFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {

    private final WebhookRepository webhooks;

    public WebhookController(WebhookRepository webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/api/v1/webhooks/{id}/retry")
    public Map<String, Object> retry(@PathVariable Long id, HttpServletRequest request) {
        Long merchantId = (Long) request.getAttribute(ApiKeyFilter.MERCHANT_ATTR);
        WebhookOutbox row = webhooks.findById(id).orElseThrow(() -> ApiException.notFound("webhook"));
        if (!row.getMerchantId().equals(merchantId)) {
            throw ApiException.notFound("webhook");
        }
        row.setStatus(WebhookStatus.PENDING);
        row.setAttempts(0);
        row.setNextAttemptAt(Instant.now());
        row.setLastError(null);
        webhooks.save(row);
        return Map.of("id", row.getId(), "status", row.getStatus().name());
    }
}
