# NovaBank and NovaTech Shop

[English](README.md) | [Polski](README.pl.md)

An educational demonstration project consisting of two Spring Boot applications:

- **NovaBank** — a banking application with accounts, transfers, cards, currencies, transaction history, 2FA and an administration panel;
- **NovaTech Shop** — an online shop with a product catalog, customer accounts, cart, orders, payments and refunds.

The project demonstrates service integration, secure session and secret management, database migrations, automated testing and basic monitoring. It is not a real bank, payment operator or production-ready system. Payments are simulated by `bank-demo` or run in Stripe test mode.

## Main capabilities

NovaBank includes, among other features:

- registration, login, password changes and login history;
- bank accounts, balances, regular and scheduled transfers, and saved recipients;
- demo cards, payments, refunds and shop payment authorization;
- currency exchange, user limits, rate limiting and 2FA/TOTP authentication;
- administrative auditing, technical data retention and PDF confirmation export.

NovaTech Shop includes, among other features:

- product catalog, customer accounts, delivery addresses and shopping cart;
- orders, payments through NovaBank or Stripe Sandbox, and refunds;
- reconciliation of payment status with the bank and payment-provider webhooks;
- independent customer authentication.

## Architecture

```text
                 +----------------------+
                 |      NovaBank        |
                 |      :8080           |
                 +----------+-----------+
                            |
                    Payment authorization API
                            |
                 +----------v-----------+
                 |    NovaTech Shop     |
                 |      :8081           |
                 +----------------------+

  Bank PostgreSQL :5300       Shop PostgreSQL :5400       Redis :6379
  Bank Actuator   :8082       Shop Actuator   :8083
```

The bank uses PostgreSQL, JPA, Flyway, Spring Security and sessions stored in Redis. The shop uses PostgreSQL/JDBC, Flyway, Spring Security and JDBC sessions; Redis supports, among other things, auxiliary data and request limiting. The production profile obtains secrets from HashiCorp Vault.

## Technology stack

- Java 25;
- Spring Boot 4.1.0;
- Maven Wrapper;
- PostgreSQL 18;
- Redis 7.4-alpine;
- Spring Security, Thymeleaf, JPA/JDBC and Spring Session;
- Flyway, Actuator, Micrometer/Prometheus and Springdoc OpenAPI;
- Testcontainers for integration tests;
- Docker Compose for local infrastructure.

## Prerequisites

Local development requires:

- JDK 25;
- Docker Engine with Docker Compose;
- Git;
- Bash, `curl` and `jq` for the guide and E2E tests;
- WSL 2/Bash on Windows, with Python 3.10+ and JDK 25 installed in WSL.

HashiCorp Vault, Stripe CLI, IntelliJ IDEA, OpenSSL and GPG are needed only for selected scenarios such as the PROD profile, TLS, Stripe Sandbox, monitoring or backups.

## Quick start

The simplest path uses NovaBank Control Center. Start it from the project directory.

### WSL/Bash (recommended), Linux or macOS

```bash
bash ./scripts/start.sh
```

The browser panel opens at `http://127.0.0.1:18110`, using Python 3.10+ in WSL/Linux without a Node build. Use Bash `.sh`, not PowerShell `.ps1`, in WSL. DEV supports local operations; TEST runs Maven/Testcontainers; PROD-like offers explicit named Vault/AppRole/bootstrap/TLS/build procedures with an additional warning and typed PROD confirmation, not remote-production control or deployment.

Use **Konfiguracja DEV** for setup and **Pliki i sekrety** for DEV/TEST secret edits. File edits do not change PostgreSQL passwords, migrate TOTP data or restart processes. **Operacje → Vault** has status, one Shamir share, AppRole setup, and Secret ID rotation/finalization for a fixed verified localhost HTTPS Vault; root tokens are rejected and input secrets are never persisted in job history. Monitoring, backups/DR, Stripe TEST, Redis/E2E, a guided startup, IntelliJ information and bounded artifact cleanup are available. PL/EN covers navigation/action names; technical content remains in its source language. CLI: `bash ./scripts/start.sh --cli`. Requirements and limitations: [Control Center in the main operating guide](docs/General_Instruction.md#control-center).

The **Operacje → Salda DEV** category also provides audited, idempotent credit/debit corrections for local active PLN accounts. **Powiadomienia** collects bank/shop WARN/ERROR logs and failed panel jobs, with a counter and scrollable history. Payment reconciliation suspends further checks after three consecutive failures without declaring the payment rejected. After updating, restart the panel and both DEV applications and check Flyway migration history; follow the [main operating guide](docs/General_Instruction.md#control-center).

Do not commit `.novabank`. It may contain local passwords, keys, certificates, tokens and logs.

## Manual startup

1. Use `bash ./scripts/start.sh` → **Konfiguracja DEV**, or `python3 -B scripts/control-center/web/dev_env_layout.py prepare` in WSL. Do not copy the mixed legacy `.env.example` into `project.env`.
2. `project.env` owns non-sensitive local infrastructure settings; `dev.env` owns DEV passwords, keys and application settings. Each key has one owner. Existing conflicts stop automatic setup; see [configuration conflict handling](docs/General_Instruction.md#manual-conflict).

3. Start the infrastructure:

   ```bash
    docker compose --env-file .novabank/env/project.env --env-file .novabank/env/dev.env up -d postgres shop-postgres redis
    docker compose --env-file .novabank/env/project.env --env-file .novabank/env/dev.env ps
   ```

4. In the browser panel, run **Buduj bank**, **Buduj sklep**, then **Uruchom aplikacje DEV**. This requires JDK 25 in WSL and current JARs. Stop only these managed applications with **Zatrzymaj aplikacje panelu**. Alternatively, use the prepared IntelliJ DEV configurations. The panel does not stop processes started by IntelliJ.

Default addresses:

- bank: <http://localhost:8080/login>;
- shop: <http://localhost:8081/novatech_shop.html>;
- bank Actuator: <http://localhost:8082/actuator/health>;
- shop Actuator: <http://localhost:8083/actuator/health>.

Local infrastructure ports are available only on `127.0.0.1`:

- bank PostgreSQL: `5300`;
- shop PostgreSQL: `5400`;
- Redis: `6379`.

## Spring profiles

### `dev`

For everyday local development. It uses local databases, Redis, HTTP and `.novabank/env/*.env` files. Vault and Stripe are not required.

### `test`

For automation. Integration tests start isolated PostgreSQL and Redis instances through Testcontainers. Do not point tests at databases used by `dev`.

### `prod`

Requires external secret management, Vault, AppRole, TLS and secure cookies. Do not use local development secrets in production.

## Tests and builds

Tests are separated using Maven naming conventions:

- `Unit tests` — classes ending in `*Test.java`. They are fast, use mocks or MVC-layer tests, and do not require Docker.
- `Integration tests` — classes ending in `*IT.java`. They start a fuller application context, Testcontainers or an HTTP flow and are executed by Failsafe.

Bank unit tests:

```bash
./mvnw -Punit-tests test
```

Shop unit tests:

```bash
./mvnw -f shop-app/pom.xml -Punit-tests test
```

Integration tests require a working Docker Engine:

```bash
./mvnw -Pintegration-tests verify
./mvnw -f shop-app/pom.xml -Pintegration-tests verify
```

The HTTP E2E smoke test is located at [scripts/e2e-smoke.sh](scripts/e2e-smoke.sh). It checks, among other things, shop registration, login, cart operations, order creation and the payment flow between the shop and bank.

The GitHub Actions pipeline in [.github/workflows/ci.yml](.github/workflows/ci.yml) runs unit tests, integration tests, the HTTP E2E flow and JAR packaging.

## Secret configuration

`.env.example` is only a template. Do not commit:

- database passwords;
- `BANK_API_KEY`, `BANK_API_KEYS` or Stripe keys;
- `TOTP_ENCRYPTION_KEY`;
- Vault AppRole data;
- keystore/truststore passwords or files;
- local backups, certificates and logs.

In `dev`, secrets are stored locally in `.novabank`. In `prod`, they should come from Vault, Kubernetes Secrets or another secret manager.

## Monitoring

The project contains Prometheus, Grafana and Alertmanager configuration. Prepare development monitoring with:

```bash
bash scripts/monitoring/monitoring.sh setup
bash scripts/monitoring/monitoring.sh start
bash scripts/monitoring/monitoring.sh status
```

Monitoring requires a separate `.novabank/monitoring/.env.monitoring` file created from [infra/monitoring/.env.monitoring.example](infra/monitoring/.env.monitoring.example). Actuator endpoints should not be exposed publicly without additional authentication and access restrictions.

## Documentation

The complete operating manual for the entire platform — **NovaBank, NovaTech Shop and Control Center** — is maintained in two equivalent language versions:

- [General_Instruction.md — English](docs/General_Instruction.md);
- [General_Instruction.pl.md — Polish](docs/General_Instruction.pl.md).

These are the only documents in `docs`. They cover first-time setup, DEV/TEST/PROD profiles, bank and shop operation, Control Center actions and their input fields, payments/Stripe TEST, Vault/TOTP/TLS, monitoring, backups/restore/DR, testing and troubleshooting. Start here before running an administrative operation; prerequisites, confirmation words and dependent steps are explained in the manual.

The optional visual introduction is an application resource under `scripts/control-center/guide`, launched with `bash scripts/control-center/interactive-guide.sh`; it supplements rather than replaces the manual.

## Security and project scope

This project is intended for learning and demonstration. Do not use it to store real funds, customer data, card numbers, production passwords or real personal data. Before public or production use, additional security audits, load testing, legal compliance review, infrastructure hardening and independent payment-flow verification would be required.

## License

This repository is distributed under the MIT License. See [LICENSE](LICENSE).
