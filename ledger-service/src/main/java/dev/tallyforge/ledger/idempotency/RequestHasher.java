package dev.tallyforge.ledger.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 fingerprint of a request, used to detect an idempotency key reused for a new body. */
public final class RequestHasher {

  private RequestHasher() {}

  public static String hash(ObjectMapper mapper, Object... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Object part : parts) {
        digest.update(mapper.writeValueAsBytes(part));
        digest.update((byte) 0);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException | JsonProcessingException e) {
      throw new IllegalStateException("Cannot hash request", e);
    }
  }

  public static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
