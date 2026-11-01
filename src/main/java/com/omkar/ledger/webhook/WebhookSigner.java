package com.omkar.ledger.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signs webhook bodies so merchants can prove a request came from us and was not modified.
 *
 * <pre>
 *   Ledger-Signature: t=1700000000,v1=hex(HMAC_SHA256(secret, "1700000000." + body))
 * </pre>
 * The timestamp is part of the signed message, so an attacker who captured a request cannot
 * replay it later: the receiver rejects timestamps older than 5 minutes (see
 * {@link WebhookSignatureVerifier}). The HMAC key is the UTF-8 bytes of the secret string.
 */
@Component
public class WebhookSigner {

    public static final String HEADER = "Ledger-Signature";
    private static final String ALGORITHM = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    public String signatureHeader(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + computeSignature(secret, timestampSeconds, body);
    }

    public static String computeSignature(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** 32 random bytes, base64. Shown to the merchant once, when the endpoint is created. */
    public static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
