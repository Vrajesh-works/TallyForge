package dev.tallyforge.ledger.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs webhook bodies as {@code t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 * Binding the timestamp into the MAC lets receivers reject replays outside a tolerance window.
 */
public final class WebhookSigner {

  public static final String HEADER = "TallyForge-Signature";
  private static final SecureRandom RANDOM = new SecureRandom();

  private WebhookSigner() {}

  public static String newSecret() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    return "whsec_" + HexFormat.of().formatHex(bytes);
  }

  public static String sign(String secret, long timestamp, String body) {
    return "t=" + timestamp + ",v1=" + hmac(secret, timestamp + "." + body);
  }

  /** Receiver-side check: valid MAC and a timestamp within {@code tolerance} of {@code now}. */
  public static boolean verify(
      String secret, String header, String body, Instant now, Duration tolerance) {
    if (header == null) {
      return false;
    }
    Long timestamp = null;
    String v1 = null;
    for (String part : header.split(",")) {
      String[] kv = part.trim().split("=", 2);
      if (kv.length != 2) {
        continue;
      }
      if (kv[0].equals("t")) {
        try {
          timestamp = Long.parseLong(kv[1]);
        } catch (NumberFormatException e) {
          return false;
        }
      } else if (kv[0].equals("v1")) {
        v1 = kv[1];
      }
    }
    if (timestamp == null || v1 == null) {
      return false;
    }
    if (Math.abs(now.getEpochSecond() - timestamp) > tolerance.toSeconds()) {
      return false;
    }
    String expected = hmac(secret, timestamp + "." + body);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), v1.getBytes(StandardCharsets.US_ASCII));
  }

  private static String hmac(String secret, String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
