package dev.tallyforge.ledger.account;

import dev.tallyforge.ledger.account.AccountDtos.AccountResponse;
import dev.tallyforge.ledger.account.AccountDtos.CreateAccountRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

  private final AccountService service;

  public AccountController(AccountService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
    return service.create(request);
  }

  @GetMapping
  public List<AccountResponse> list() {
    return service.list();
  }

  @GetMapping("/{id}")
  public AccountResponse get(@PathVariable UUID id) {
    return service.get(id);
  }
}
