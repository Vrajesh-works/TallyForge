package dev.tallyforge.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TallyForgeApplication {

  public static void main(String[] args) {
    SpringApplication.run(TallyForgeApplication.class, args);
  }
}
