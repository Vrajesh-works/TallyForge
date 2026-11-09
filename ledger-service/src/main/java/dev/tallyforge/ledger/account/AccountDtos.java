package dev.tallyforge.ledger.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AccountDtos {

  private AccountDtos() {}

  public record CreateAccountRequest(
      @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._:-]+") String code,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
      AccountFlags flags) {}

  public record AccountResponse(
      UUID id,
      String code,
      String name,
      String currency,
      AccountFlags flags,
      long debitsPending,
      long debitsPosted,
      long creditsPending,
      long creditsPosted,
      long available,
      Instant createdAt) {

    public static AccountResponse from(Account a) {
      return new AccountResponse(
          a.getId(),
          a.getCode(),
          a.getName(),
          a.getCurrency(),
          a.getFlags(),
          a.getDebitsPending(),
          a.getDebitsPosted(),
          a.getCreditsPending(),
          a.getCreditsPosted(),
          a.available(),
          a.getCreatedAt());
    }
  }
}
