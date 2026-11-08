package dev.tallyforge.ledger.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

  @Bean
  NewTopic ledgerEventsTopic(TopicsProperties topics) {
    return TopicBuilder.name(topics.ledgerEvents()).partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic webhooksDlqTopic(TopicsProperties topics) {
    return TopicBuilder.name(topics.webhooksDlq()).partitions(1).replicas(1).build();
  }
}
