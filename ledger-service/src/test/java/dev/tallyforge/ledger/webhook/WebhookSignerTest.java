package dev.tallyforge.ledger.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {

  private static final String SECRET = "whsec_test";
  private static final String BODY = "{\"id\":\"evt_1\"}";
  private static final Duration TOLERANCE = Duration.ofMinutes(5);

  @Test
  void signatureRoundTrips() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    String header = WebhookSigner.sign(SECRET, now.getEpochSecond(), BODY);
    assertThat(header).startsWith("t=" + now.getEpochSecond() + ",v1=");
    assertThat(WebhookSigner.verify(SECRET, header, BODY, now, TOLERANCE)).isTrue();
  }

  @Test
  void matchesAnIndependentlyComputedVector() {
    // python3: hmac.new(b"whsec_test", b'1700000000.{"id":"evt_1"}', hashlib.sha256).hexdigest()
    assertThat(WebhookSigner.sign(SECRET, 1_700_000_000L, BODY))
        .isEqualTo(
            "t=1700000000,v1=c89214b5b5da833daed6f0b8c5bb6bd58cea9022bd80ccc78230f3942d632925");
  }

  @Test
  void rejectsTamperedBodyWrongSecretAndReplays() {
    Instant now = Instant.now();
    String header = WebhookSigner.sign(SECRET, now.getEpochSecond(), BODY);
    assertThat(WebhookSigner.verify(SECRET, header, BODY + " ", now, TOLERANCE)).isFalse();
    assertThat(WebhookSigner.verify("whsec_other", header, BODY, now, TOLERANCE)).isFalse();
    assertThat(WebhookSigner.verify(SECRET, header, BODY, now.plusSeconds(301), TOLERANCE))
        .isFalse();
  }

  @Test
  void rejectsMalformedHeaders() {
    Instant now = Instant.now();
    assertThat(WebhookSigner.verify(SECRET, null, BODY, now, TOLERANCE)).isFalse();
    assertThat(WebhookSigner.verify(SECRET, "garbage", BODY, now, TOLERANCE)).isFalse();
    assertThat(WebhookSigner.verify(SECRET, "t=abc,v1=00", BODY, now, TOLERANCE)).isFalse();
    assertThat(WebhookSigner.verify(SECRET, "t=" + now.getEpochSecond(), BODY, now, TOLERANCE))
        .isFalse();
  }

  @Test
  void secretsAreRandom() {
    assertThat(WebhookSigner.newSecret())
        .startsWith("whsec_")
        .isNotEqualTo(WebhookSigner.newSecret());
  }
}
