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
- WSL 2 and PowerShell are recommended on Windows.

HashiCorp Vault, Stripe CLI, IntelliJ IDEA, OpenSSL and GPG are needed only for selected scenarios such as the PROD profile, TLS, Stripe Sandbox, monitoring or backups.

## Quick start

The simplest path uses NovaBank Control Center. Start it from the project directory.

### Linux, WSL or macOS

```bash
bash scripts/control-center/control-center.sh
```

### Windows PowerShell

```powershell
.\scripts\control-center\start.ps1
```

Choose the first-run option. The Control Center prepares the local `.novabank` directory, environment files and required development secrets, and helps start PostgreSQL and Redis.

Do not commit `.novabank`. It may contain local passwords, keys, certificates, tokens and logs.

## Manual startup

1. Prepare local configuration from [.env.example](.env.example). Replace values such as `change-me-*`, `replace-with-*` and `generate-*` with your own values.
2. Create `.novabank/env/project.env` and `.novabank/env/dev.env`. You can generate the development environment with:

   ```powershell
   .\scripts\generate-dev-env.ps1
   ```

3. Start the infrastructure:

   ```bash
   docker compose --env-file .novabank/env/project.env up -d postgres shop-postgres redis
   docker compose --env-file .novabank/env/project.env ps
   ```

4. Start both applications with the `dev` profile, preferably through IntelliJ IDEA or the prepared script:

   ```powershell
   .\scripts\start-dev.ps1
   ```

   The script stops existing development processes and starts the bank and shop with the local environment file. Stop the applications with:

   ```powershell
   .\scripts\stop-dev.ps1
   ```

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

```powershell
.\mvnw.cmd -Punit-tests test
```

Shop unit tests:

```powershell
.\mvnw.cmd -f shop-app\pom.xml -Punit-tests test
```

Integration tests require a working Docker Engine:

```powershell
.\mvnw.cmd -Pintegration-tests verify
.\mvnw.cmd -f shop-app\pom.xml -Pintegration-tests verify
```

Linux/WSL equivalents:

```bash
./mvnw -Punit-tests test
./mvnw -f shop-app/pom.xml -Punit-tests test
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

- [General setup and operations guide](docs/General_Instruction.md) — complete guide to WSL, Docker, profiles, Vault, TLS, monitoring, backups and diagnostics;
- [Polish version of the main guide](docs/General_Instruction.pl.md);
- [Interactive guide](docs/interactive-guide.html) — a visual introduction to the project;
- [Software test types](docs/Software_Test_Types.md) — testing strategy overview;
- [Polish version of the testing guide](docs/Software_Test_Types.pl.md);
- [Regulations generator guide](docs/regulamin/README.md);
- [Local infrastructure configuration](docker-compose.yaml);
- [Example environment variables](.env.example).

## Security and project scope

This project is intended for learning and demonstration. Do not use it to store real funds, customer data, card numbers, production passwords or real personal data. Before public or production use, additional security audits, load testing, legal compliance review, infrastructure hardening and independent payment-flow verification would be required.

## License

This repository is distributed under the MIT License. See [LICENSE](LICENSE).
