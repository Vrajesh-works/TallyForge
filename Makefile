SHELL := /bin/bash
MVN := cd ledger-service && ./mvnw -B
API_PORT ?= 8080
DASHBOARD_PORT ?= 3000

.PHONY: up down logs test test-unit test-dashboard lint fmt seed

up: ## Build and start the whole stack
	docker compose up --build -d
	@echo "Dashboard: http://localhost:$(DASHBOARD_PORT)  API: http://localhost:$(API_PORT)"

down: ## Stop the stack and delete its data volume
	docker compose down -v

logs:
	docker compose logs -f ledger

test: test-dashboard ## Unit + integration tests (integration tests need Docker for Testcontainers)
	$(MVN) verify

test-unit:
	$(MVN) test

test-dashboard:
	cd dashboard && npm ci --no-audit --no-fund && npm run lint && npm run typecheck && npm test

lint:
	$(MVN) spotless:check
	cd dashboard && npm run lint

fmt:
	$(MVN) spotless:apply

seed: ## Replay PaySim-shaped traffic into a running stack
	python3 scripts/paysim_replay.py --generate 500 --api http://localhost:$(API_PORT)
