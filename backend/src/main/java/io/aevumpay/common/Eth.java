package io.aevumpay.common;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

public final class Eth {

    public static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    public static final String ZERO_BYTES32 = "0x" + "0".repeat(64);

    private static final SecureRandom RANDOM = new SecureRandom();

    private Eth() {}

    public static String normalizeAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (!value.startsWith("0x") && !value.startsWith("0X")) {
            value = "0x" + value;
        }
        if (!value.matches("(?i)0x[0-9a-f]{40}")) {
            throw ApiException.badRequest("bad_address", "address must be 20 bytes hex");
        }
        return "0x" + value.substring(2).toLowerCase();
    }

    public static boolean isZeroAddress(String address) {
        return address == null || ZERO_ADDRESS.equalsIgnoreCase(address);
    }

    /** 与前端 ethers.keccak256(toUtf8Bytes(orderId)) 一致。空订单号映射为 bytes32(0)，链上不去重。 */
    public static String orderRef(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return ZERO_BYTES32;
        }
        return hex(Hash.sha3(orderId.getBytes(StandardCharsets.UTF_8)));
    }

    public static boolean isZeroBytes32(String hex) {
        return hex == null || ZERO_BYTES32.equalsIgnoreCase(hex);
    }

    public static byte[] bytes32(String hex) {
        byte[] raw = Numeric.hexStringToByteArray(hex);
        if (raw.length != 32) {
            throw ApiException.badRequest("bad_bytes32", "expected 32 bytes");
        }
        return raw;
    }

    public static String hex(byte[] bytes) {
        String encoded = Numeric.toHexString(bytes);
        return encoded.startsWith("0x") ? encoded : "0x" + encoded;
    }

    public static String topicUint(BigInteger value) {
        return Numeric.toHexStringWithPrefixZeroPadded(value, 64);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    public static String chainIdHex(long chainId) {
        return "0x" + Long.toHexString(chainId);
    }
}
