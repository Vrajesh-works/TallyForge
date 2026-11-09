package dev.tallyforge.ledger.account;

import dev.tallyforge.ledger.account.AccountDtos.AccountResponse;
import dev.tallyforge.ledger.account.AccountDtos.CreateAccountRequest;
import dev.tallyforge.ledger.common.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

  private final AccountRepository accounts;

  public AccountService(AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Transactional
  public AccountResponse create(CreateAccountRequest request) {
    if (accounts.existsByCode(request.code())) {
      throw ApiException.conflict("account_exists", "Account code " + request.code() + " exists");
    }
    AccountFlags flags = request.flags() == null ? AccountFlags.NONE : request.flags();
    Account account =
        new Account(UUID.randomUUID(), request.code(), request.name(), request.currency(), flags);
    return AccountResponse.from(accounts.save(account));
  }

  @Transactional(readOnly = true)
  public AccountResponse get(UUID id) {
    return AccountResponse.from(
        accounts.findById(id).orElseThrow(() -> ApiException.notFound("Account", id)));
  }

  @Transactional(readOnly = true)
  public List<AccountResponse> list() {
    return accounts.findAll(Sort.by("code")).stream().map(AccountResponse::from).toList();
  }
}
