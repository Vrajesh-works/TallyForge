package dev.tallyforge.ledger.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads a topic from the beginning with a throwaway consumer group. */
public final class KafkaProbe {

  private KafkaProbe() {}

  public static List<JsonNode> await(
      String bootstrap, String topic, Predicate<JsonNode> match, int count, Duration timeout)
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> props =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG,
            "probe-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
    List<JsonNode> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      Instant deadline = Instant.now().plus(timeout);
      while (found.size() < count && Instant.now().isBefore(deadline)) {
        for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(250))) {
          JsonNode node = mapper.readTree(rec.value());
          if (match.test(node)) {
            found.add(node);
          }
        }
      }
    }
    return found;
  }
}
