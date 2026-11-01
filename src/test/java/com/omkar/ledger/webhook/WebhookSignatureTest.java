package com.omkar.ledger.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omkar.ledger.webhook.WebhookSignatureVerifier.SignatureVerificationException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    private static final String SECRET = "c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlYw==";
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"payment.succeeded\",\"created\":1700000000,\"data\":{}}";
    private static final long NOW = 1_700_000_000L;

    private final WebhookSigner signer = new WebhookSigner();
    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(
            Duration.ofMinutes(5), Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    @Test
    void headerHasTimestampAndHexSignature() {
        String header = signer.signatureHeader(SECRET, NOW, BODY);
        assertThat(header).matches("t=1700000000,v1=[0-9a-f]{64}");
    }

    @Test
    void signatureIsDeterministic() {
        assertThat(WebhookSigner.computeSignature(SECRET, NOW, BODY))
                .isEqualTo(WebhookSigner.computeSignature(SECRET, NOW, BODY));
    }

    @Test
    void signsTimestampDotBodyWithHmacSha256() throws Exception {
        // Independent computation of the documented scheme: hex(HMAC_SHA256(secret, "<t>.<body>"))
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal((NOW + "." + BODY).getBytes(StandardCharsets.UTF_8)));
        assertThat(WebhookSigner.computeSignature(SECRET, NOW, BODY)).isEqualTo(expected);
    }

    @Test
    void hmacImplementationMatchesRfc4231TestCase2() throws Exception {
        // Sanity check of the JDK primitive against the published test vector.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("Jefe".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String digest = HexFormat.of().formatHex(
                mac.doFinal("what do ya want for nothing?".getBytes(StandardCharsets.UTF_8)));
        assertThat(digest).isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void validSignatureVerifies() throws Exception {
        String header = signer.signatureHeader(SECRET, NOW, BODY);
        verifier.verify(BODY, header, SECRET);
        assertThat(verifier.isValid(BODY, header, SECRET)).isTrue();
    }

    @Test
    void tamperedBodyIsRejected() {
        String header = signer.signatureHeader(SECRET, NOW, BODY);
        String tampered = BODY.replace("payment.succeeded", "refund.created");
        assertThatThrownBy(() -> verifier.verify(tampered, header, SECRET))
                .isInstanceOf(SignatureVerificationException.class)
                .hasMessageContaining("No signature matches");
    }

    @Test
    void wrongSecretIsRejected() {
        String header = signer.signatureHeader(SECRET, NOW, BODY);
        assertThat(verifier.isValid(BODY, header, "some-other-secret")).isFalse();
    }

    @Test
    void expiredTimestampIsRejected() {
        long sixMinutesAgo = NOW - 6 * 60;
        String header = signer.signatureHeader(SECRET, sixMinutesAgo, BODY);
        assertThatThrownBy(() -> verifier.verify(BODY, header, SECRET))
                .isInstanceOf(SignatureVerificationException.class)
                .hasMessageContaining("tolerance");
    }

    @Test
    void timestampFarInTheFutureIsRejected() {
        String header = signer.signatureHeader(SECRET, NOW + 10 * 60, BODY);
        assertThat(verifier.isValid(BODY, header, SECRET)).isFalse();
    }

    @Test
    void timestampWithinToleranceIsAccepted() {
        String header = signer.signatureHeader(SECRET, NOW - 4 * 60, BODY);
        assertThat(verifier.isValid(BODY, header, SECRET)).isTrue();
    }

    @Test
    void changingTheTimestampInTheHeaderBreaksTheSignature() {
        String header = signer.signatureHeader(SECRET, NOW - 60, BODY).replace("t=" + (NOW - 60), "t=" + NOW);
        assertThat(verifier.isValid(BODY, header, SECRET)).isFalse();
    }

    @Test
    void malformedHeadersAreRejected() {
        assertThat(verifier.isValid(BODY, "garbage", SECRET)).isFalse();
        assertThat(verifier.isValid(BODY, "t=abc,v1=00", SECRET)).isFalse();
        assertThat(verifier.isValid(BODY, "v1=00", SECRET)).isFalse();
        assertThat(verifier.isValid(BODY, "t=" + NOW, SECRET)).isFalse();
        assertThat(verifier.isValid(BODY, null, SECRET)).isFalse();
    }

    @Test
    void acceptsAnyMatchingSignatureDuringSecretRotation() {
        String good = WebhookSigner.computeSignature(SECRET, NOW, BODY);
        String header = "t=" + NOW + ",v1=" + "0".repeat(64) + ",v1=" + good;
        assertThat(verifier.isValid(BODY, header, SECRET)).isTrue();
    }

    @Test
    void generatedSecretsAre32RandomBytes() {
        String a = WebhookSigner.generateSecret();
        String b = WebhookSigner.generateSecret();
        assertThat(Base64.getDecoder().decode(a)).hasSize(32);
        assertThat(a).isNotEqualTo(b);
    }
}
