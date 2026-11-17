package io.aevumpay.merchant;

import io.aevumpay.common.ApiException;
import io.aevumpay.common.Eth;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class NonceService {

    private final Map<String, Issued> nonces = new ConcurrentHashMap<>();

    public Issued issue(String address) {
        String normalized = Eth.normalizeAddress(address);
        String nonce = UUID.randomUUID().toString();
        Issued issued = new Issued(normalized, nonce, message(normalized, nonce), Instant.now().plusSeconds(600));
        nonces.put(normalized, issued);
        return issued;
    }

    public void consume(String address, String nonce) {
        Issued issued = nonces.remove(address);
        if (issued == null || issued.expiresAt().isBefore(Instant.now()) || !issued.nonce().equals(nonce)) {
            throw ApiException.unauthorized("nonce expired or unknown");
        }
    }

    public static String message(String address, String nonce) {
        return "Aevum Pay merchant registration\nAddress: " + address + "\nNonce: " + nonce;
    }

    public record Issued(String address, String nonce, String message, Instant expiresAt) {}
}
