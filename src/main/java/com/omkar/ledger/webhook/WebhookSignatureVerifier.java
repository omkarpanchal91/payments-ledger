package com.omkar.ledger.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference implementation for <b>merchants</b>: how to verify a webhook from this service.
 * It has no Spring dependencies so it can be copied into a merchant's codebase as is.
 *
 * <pre>{@code
 * var verifier = new WebhookSignatureVerifier();
 * verifier.verify(rawRequestBody, request.getHeader("Ledger-Signature"), endpointSecret);
 * }</pre>
 *
 * Rules: use the <em>raw</em> body bytes (not re-serialized JSON); reject timestamps outside the
 * tolerance (replay protection); compare signatures in constant time ({@link MessageDigest#isEqual})
 * so an attacker can't learn the correct signature byte-by-byte from response timing.
 */
public final class WebhookSignatureVerifier {

    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private final Duration tolerance;
    private final Clock clock;

    public WebhookSignatureVerifier() {
        this(DEFAULT_TOLERANCE, Clock.systemUTC());
    }

    public WebhookSignatureVerifier(Duration tolerance, Clock clock) {
        this.tolerance = tolerance;
        this.clock = clock;
    }

    public void verify(String payload, String signatureHeader, String secret) throws SignatureVerificationException {
        if (payload == null || signatureHeader == null || secret == null) {
            throw new SignatureVerificationException("Missing payload, signature header or secret");
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("t")) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new SignatureVerificationException("Invalid timestamp in signature header");
                }
            } else if (key.equals("v1")) {
                signatures.add(value);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new SignatureVerificationException("Signature header must contain t= and v1=");
        }

        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > tolerance.toSeconds()) {
            throw new SignatureVerificationException("Timestamp outside the tolerance window");
        }

        byte[] expected = WebhookSigner.computeSignature(secret, timestamp, payload).getBytes(StandardCharsets.UTF_8);
        for (String candidate : signatures) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                return;
            }
        }
        throw new SignatureVerificationException("No signature matches the expected signature");
    }

    public boolean isValid(String payload, String signatureHeader, String secret) {
        try {
            verify(payload, signatureHeader, secret);
            return true;
        } catch (SignatureVerificationException e) {
            return false;
        }
    }

    public static class SignatureVerificationException extends Exception {
        public SignatureVerificationException(String message) {
            super(message);
        }
    }
}
