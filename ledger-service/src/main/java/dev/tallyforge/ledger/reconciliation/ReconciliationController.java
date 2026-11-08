package dev.tallyforge.ledger.reconciliation;

import dev.tallyforge.ledger.reconciliation.ReconciliationService.RunResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reconciliation/runs")
public class ReconciliationController {

  private final ReconciliationService service;

  public ReconciliationController(ReconciliationService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RunResponse runNow() {
    return service.run("MANUAL");
  }

  @GetMapping
  public List<RunResponse> recent(@RequestParam(defaultValue = "30") int limit) {
    return service.recent(limit);
  }

  @GetMapping("/{id}")
  public RunResponse get(@PathVariable UUID id) {
    return service.get(id);
  }
}
