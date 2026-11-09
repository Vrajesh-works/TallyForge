package dev.tallyforge.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApplicationIT extends IntegrationTest {

  @Test
  void healthEndpointReportsUp() {
    var response = http.getForEntity("/actuator/health", Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("status", "UP");
  }
}
