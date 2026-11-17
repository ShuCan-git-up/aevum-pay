package io.aevumpay.merchant;

import io.aevumpay.common.ApiException;
import io.aevumpay.common.Eth;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

@Service
public class MerchantService {

    private final MerchantRepository merchants;
    private final NonceService nonces;

    public MerchantService(MerchantRepository merchants, NonceService nonces) {
        this.merchants = merchants;
        this.nonces = nonces;
    }

    @Transactional
    public Registration register(String name, String walletAddress, String nonce, String signature, String webhookUrl) {
        String address = Eth.normalizeAddress(walletAddress);
        if (name == null || name.isBlank() || name.length() > 100) {
            throw ApiException.badRequest("bad_name", "name is required");
        }
        if (nonce == null || nonce.isBlank() || signature == null || signature.isBlank()) {
            throw ApiException.badRequest("bad_signature", "nonce and signature are required");
        }
        String message = NonceService.message(address, nonce);
        String recovered = recover(message, signature);
        if (!address.equals(recovered)) {
            throw ApiException.unauthorized("signature does not match wallet");
        }
        nonces.consume(address, nonce);
        if (webhookUrl != null && !webhookUrl.isBlank() && !webhookUrl.startsWith("http://") && !webhookUrl.startsWith("https://")) {
            throw ApiException.badRequest("bad_webhook", "webhookUrl must be http(s)");
        }

        MerchantEntity merchant = merchants.findByWalletAddress(address).orElseGet(MerchantEntity::new);
        merchant.setWalletAddress(address);
        merchant.setName(name.trim());
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            merchant.setWebhookUrl(webhookUrl.trim());
        }
        String apiKey = Eth.randomHex(32);
        String webhookSecret = Eth.randomHex(24);
        merchant.setApiKeyHash(Eth.sha256Hex(apiKey));
        merchant.setWebhookSecret(webhookSecret);
        merchants.save(merchant);
        return new Registration(merchant.getId(), address, apiKey, webhookSecret, merchant.getWebhookUrl());
    }

    @Transactional
    public MerchantEntity requireByApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw ApiException.unauthorized("missing X-Api-Key");
        }
        return merchants.findByApiKeyHash(Eth.sha256Hex(apiKey.trim()))
                .orElseThrow(() -> ApiException.unauthorized("invalid api key"));
    }

    @Transactional
    public MerchantEntity updateWebhook(Long merchantId, String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank() || (!webhookUrl.startsWith("http://") && !webhookUrl.startsWith("https://"))) {
            throw ApiException.badRequest("bad_webhook", "webhookUrl must be http(s)");
        }
        MerchantEntity merchant = merchants.findById(merchantId).orElseThrow(() -> ApiException.notFound("merchant"));
        merchant.setWebhookUrl(webhookUrl.trim());
        return merchant;
    }

    public static String recover(String message, String signature) {
        try {
            Sign.SignatureData data = Sign.signatureDataFromHex(signature.trim());
            BigInteger key = Sign.signedPrefixedMessageToKey(message.getBytes(StandardCharsets.UTF_8), data);
            return "0x" + Keys.getAddress(key);
        } catch (SignatureException exception) {
            throw ApiException.unauthorized("bad signature");
        }
    }

    public record Registration(Long merchantId, String walletAddress, String apiKey, String webhookSecret, String webhookUrl) {}
}
