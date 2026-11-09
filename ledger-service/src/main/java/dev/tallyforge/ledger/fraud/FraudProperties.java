package dev.tallyforge.ledger.fraud;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Thresholds for the rule-based fraud screen. Amounts are minor units. */
@ConfigurationProperties(prefix = "tallyforge.fraud")
public record FraudProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("1000000") long largeAmount,
    @DefaultValue("100000") long drainMinAmount,
    @DefaultValue("20") int velocityMax,
    @DefaultValue("PT1M") Duration velocityWindow) {}
