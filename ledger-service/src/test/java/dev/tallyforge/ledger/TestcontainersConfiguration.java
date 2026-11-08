package dev.tallyforge.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL and a Kafka-compatible broker (Redpanda) for integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgres() {
    return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
  }

  @Bean
  @ServiceConnection
  RedpandaContainer redpanda() {
    return new RedpandaContainer(
        DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v24.2.7"));
  }
}
