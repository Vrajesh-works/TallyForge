package dev.tallyforge.ledger.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "tallyforge.topics")
public record TopicsProperties(
    @DefaultValue("tallyforge.ledger.events") String ledgerEvents,
    @DefaultValue("tallyforge.webhooks.dlq") String webhooksDlq) {}
