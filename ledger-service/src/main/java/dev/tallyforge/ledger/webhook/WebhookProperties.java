package dev.tallyforge.ledger.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "tallyforge.webhooks")
public record WebhookProperties(
    @DefaultValue("true") boolean dispatcherEnabled,
    @DefaultValue("6") int maxAttempts,
    @DefaultValue("PT2S") Duration baseDelay,
    @DefaultValue("PT10M") Duration maxDelay,
    @DefaultValue("PT5S") Duration timeout,
    @DefaultValue("20") int batchSize) {}
