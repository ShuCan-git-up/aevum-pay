package io.aevumpay.merchant;

import io.aevumpay.config.ApiKeyFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MerchantController {

    private final MerchantService merchants;
    private final NonceService nonces;

    public MerchantController(MerchantService merchants, NonceService nonces) {
        this.merchants = merchants;
        this.nonces = nonces;
    }

    @GetMapping("/api/v1/auth/nonce")
    public NonceService.Issued nonce(@RequestParam String address) {
        return nonces.issue(address);
    }

    @PostMapping("/api/v1/merchants")
    public MerchantService.Registration register(@RequestBody RegisterRequest request) {
        return merchants.register(
                request.name(), request.walletAddress(), request.nonce(), request.signature(), request.webhookUrl());
    }

    @PutMapping("/api/v1/merchants/me")
    public Map<String, String> updateWebhook(@RequestBody WebhookRequest request, HttpServletRequest http) {
        Long merchantId = (Long) http.getAttribute(ApiKeyFilter.MERCHANT_ATTR);
        var merchant = merchants.updateWebhook(merchantId, request.webhookUrl());
        return Map.of("walletAddress", merchant.getWalletAddress(), "webhookUrl", merchant.getWebhookUrl());
    }

    public record RegisterRequest(String name, String walletAddress, String nonce, String signature, String webhookUrl) {}

    public record WebhookRequest(String webhookUrl) {}
}
