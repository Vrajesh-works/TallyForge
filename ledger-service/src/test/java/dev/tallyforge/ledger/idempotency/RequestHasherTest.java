package dev.tallyforge.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

  private final ObjectMapper mapper = new ObjectMapper();

  record Body(String a, long amount) {}

  @Test
  void sameRequestHashesTheSame() {
    assertThat(RequestHasher.hash(mapper, "scope", new Body("x", 10)))
        .isEqualTo(RequestHasher.hash(mapper, "scope", new Body("x", 10)))
        .hasSize(64);
  }

  @Test
  void differentBodyOrScopeChangesTheHash() {
    String base = RequestHasher.hash(mapper, "scope", new Body("x", 10));
    assertThat(RequestHasher.hash(mapper, "scope", new Body("x", 11))).isNotEqualTo(base);
    assertThat(RequestHasher.hash(mapper, "other", new Body("x", 10))).isNotEqualTo(base);
  }

  @Test
  void partBoundariesMatter() {
    assertThat(RequestHasher.hash(mapper, "ab", "c"))
        .isNotEqualTo(RequestHasher.hash(mapper, "a", "bc"));
    assertThat(RequestHasher.hash(mapper, Map.of("k", 1))).isNotEqualTo(RequestHasher.hash(mapper));
  }
}
