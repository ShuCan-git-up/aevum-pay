package io.aevumpay.webhook;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.web3j.utils.Numeric;

/** Stripe 风格：{@code t=<unix>,v1=HMAC_SHA256(secret, t + "." + body)}。 */
public final class HmacSigner {

    private HmacSigner() {}

    public static String header(String secret, String body, Instant now) {
        long timestamp = now.getEpochSecond();
        String signed = timestamp + "." + body;
        return "t=" + timestamp + ",v1=" + hmac(secret, signed);
    }

    public static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String hex = Numeric.toHexString(raw);
            return hex.startsWith("0x") ? hex.substring(2) : hex;
        } catch (Exception exception) {
            throw new IllegalStateException("hmac failed", exception);
        }
    }
}
