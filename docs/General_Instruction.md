# NovaBank and NovaTech Shop

[English](General_Instruction.md) | [Polski](General_Instruction.pl.md)

## General setup and operations guide

This is the main English guide for the local NovaBank and NovaTech Shop workflow. It covers WSL 2, Docker Engine, PostgreSQL, Redis, Spring/Maven profiles, IntelliJ IDEA, HashiCorp Vault, TLS/HTTPS, monitoring, Stripe test payments, automated tests, auditing and backups.

This is an educational local environment. It is not a production bank or a real payment operator. Stripe uses test mode and `bank-demo` simulates payments in the application databases.

> **Use your own project path.** Examples use `<PROJECT_ROOT>` and `<PROJECT_WSL>`. Replace them with the directory containing your clone. This applies to PowerShell, WSL/Linux/macOS, IntelliJ IDEA, EnvFile configuration, Control Center, monitoring, Vault and backups.

The Polish edition with extended operational notes is available at [General_Instruction.pl.md](General_Instruction.pl.md).

## 1. Start here

The recommended order is:

1. prepare WSL, Docker, Java and the project directory;
2. run NovaBank Control Center and complete first-run preparation;
3. start PostgreSQL and Redis;
4. select the `dev`, `test` or `prod` profile;
5. start the bank and shop;
6. enable monitoring only after both applications are running;
7. run tests and E2E checks;
8. configure Vault, TLS and production-only services only when needed.

For a visual introduction, run:

```bash
cd "<PROJECT_WSL>"
bash scripts/control-center/interactive-guide.sh
```

## 2. Architecture and components

The project contains two independent Spring Boot applications:

- NovaBank, normally on port `8080`;
- NovaTech Shop, normally on port `8081`.

Management endpoints use separate ports:

- bank Actuator: `8082`;
- shop Actuator: `8083`.

Infrastructure:

```text
bank-postgres  -> 127.0.0.1:5300 -> PostgreSQL database bankdb
shop-postgres  -> 127.0.0.1:5400 -> PostgreSQL database shopdb
bank-redis     -> 127.0.0.1:6379 -> Redis
```

The bank handles users, accounts, balances, transfers, recipients, cards, currencies, 2FA, history, administration and the payment-authorization API. The shop handles the product catalog, customers, cart, orders, bank-demo or Stripe payments and refunds.

Docker runs infrastructure services; local Java applications may still run as IntelliJ processes. Therefore, running containers do not imply that ports `8080` and `8081` are available.

## 3. Prerequisites

Required or commonly used components:

- JDK 25 and `keytool`;
- Git;
- Docker Engine and Docker Compose;
- Bash, `curl` and `jq`;
- WSL 2 on Windows;
- Maven Wrapper included in the repository.

Optional components:

- HashiCorp Vault for `prod`;
- OpenSSL and GPG for TLS and recovery material;
- Prometheus, Grafana and Alertmanager for monitoring;
- Stripe CLI for Stripe webhook testing;
- IntelliJ IDEA for IDE-based startup.

Docker Desktop is not required when Docker Engine is available through WSL 2.

## 4. First run with NovaBank Control Center

From WSL, Linux or macOS:

```bash
cd "<PROJECT_WSL>"
bash scripts/control-center/control-center.sh
```

From Windows PowerShell:

```powershell
Set-Location '<PROJECT_ROOT>'
.\scripts\control-center\start.ps1
```

Choose **First run**. Control Center checks Bash, Docker, Compose, Java, `keytool`, `curl`, `jq`, OpenSSL and GPG; creates the ignored `.novabank` directory; prepares project and development environment files; reports missing components; and can prepare monitoring.

Do not commit `.novabank`. It can contain passwords, tokens, certificates, keys, logs and bootstrap files.

If the repository is in another directory, run scripts from that directory. The scripts calculate the project root from their own location. In IntelliJ, set the working directory to the actual project directory and point EnvFile to the actual `.novabank/env` file.

## 5. Project-local configuration and infrastructure

Prepare the local environment from the example:

```bash
cd "<PROJECT_WSL>"
mkdir -p .novabank/env .novabank/monitoring .novabank/tls/vault .novabank/tls/prod
cp -n .env.example .novabank/env/project.env
chmod 600 .novabank/env/project.env
```

Alternatively select the corresponding option in Control Center. Fill `project.env` with local values. Do not replace passwords used by existing database containers without a data-migration plan.

Start the databases and Redis:

```bash
docker compose --env-file .novabank/env/project.env up -d postgres shop-postgres redis
docker compose --env-file .novabank/env/project.env ps
docker ps
```

The database containers should become `healthy`; Redis should be `running`.

Expected local ports:

```text
bank-postgres: 127.0.0.1:5300 -> PostgreSQL 5432
shop-postgres: 127.0.0.1:5400 -> PostgreSQL 5432
bank-redis:    127.0.0.1:6379 -> Redis 6379
```

Use the database user and password from `project.env`. Do not assume that a `postgres` role exists in the container.

## 6. Accounts and test data

For a basic manual flow you need:

1. a regular bank user with an active PLN account;
2. a bank administrator account;
3. an independent shop customer account;
4. an active bank account configured as the shop merchant account;
5. optionally, a metrics account;
6. optionally, a Stripe Sandbox account.

Shop registration is independent from bank registration. A merchant bank account is not created by shop registration. For a NovaBank payment, the customer must authenticate in the bank and use an active account. Never use real passwords, card numbers or production data in this environment.

## 7. Spring profiles

### `dev`

The daily local profile. Secrets are read from `.novabank/env/dev.env`; applications usually run from IntelliJ. It uses local databases, Redis and HTTP. Vault and Stripe are not required.

Bank DEV should use:

- active profile `dev`;
- project directory as the working directory;
- EnvFile `.novabank/env/dev.env`;
- application port `8080`;
- management port `8082`.

Shop DEV uses the same arrangement with application port `8081` and management port `8083`.

Restart both Java processes after changing API keys, database passwords, TOTP keys, Stripe settings or metrics credentials. Refreshing the browser does not reload process environment variables.

### `test`

The automation profile. Integration tests use isolated PostgreSQL and Redis instances through Testcontainers. Do not use production databases, Vault AppRole credentials or production certificates.

The GitHub runner must be able to access Docker images. A Docker/Testcontainers failure is an environment failure, not automatically an application-logic failure.

### `prod`

The production-like profile requires Vault, AppRole, TLS, secure cookies, truststores and keystores. Do not use `.novabank/env/dev.env` for `prod`. Bootstrap files should contain only values needed to connect to Vault; application secrets should be read from Vault or another secure runtime mechanism.

## 8. Starting the applications

The prepared IntelliJ run configurations are the preferred local route. Start both services with the same profile and the correct EnvFile.

Manual Windows helper scripts are also available:

```powershell
.\scripts\start-dev.ps1
.\scripts\stop-dev.ps1
```

Linux/WSL users can use the shell-based Control Center. Do not start two bank or two shop processes on the same ports.

Default URLs:

- bank login: <http://localhost:8080/login>;
- shop: <http://localhost:8081/novatech_shop.html>;
- bank health: <http://localhost:8082/actuator/health>;
- shop health: <http://localhost:8083/actuator/health>.

## 9. Maven and automated tests

Test classes follow this convention:

- `*Test.java` — unit tests and fast MVC/component tests;
- `*IT.java` — integration tests executed by Failsafe.

Bank unit tests:

```powershell
.\mvnw.cmd -Punit-tests test
```

Shop unit tests:

```powershell
.\mvnw.cmd -f shop-app\pom.xml -Punit-tests test
```

Bank integration tests:

```powershell
.\mvnw.cmd -Pintegration-tests verify
```

Shop integration tests:

```powershell
.\mvnw.cmd -f shop-app\pom.xml -Pintegration-tests verify
```

Linux/WSL equivalents:

```bash
./mvnw -Punit-tests test
./mvnw -f shop-app/pom.xml -Punit-tests test
./mvnw -Pintegration-tests verify
./mvnw -f shop-app/pom.xml -Pintegration-tests verify
```

Unit tests do not require a running Docker environment. Integration tests do. If Maven reports a missing `JAVA_HOME`, check `java -version`, `echo "$JAVA_HOME"` and `which java` in WSL, or configure JDK 25 in IntelliJ on Windows.

Maven profiles and Spring profiles are different: Maven selects the build/test mode while Spring selects application configuration.

GitHub Actions in [.github/workflows/ci.yml](../.github/workflows/ci.yml) runs bank and shop unit tests, Testcontainers integration tests, the HTTP E2E flow and JAR packaging. It runs for pushes, pull requests and manual dispatch. It does not deploy the production profile or use production secrets.

## 10. HTTP E2E smoke test

The script [scripts/e2e-smoke.sh](../scripts/e2e-smoke.sh) checks a real bank-shop flow. It:

1. waits for bank and shop health endpoints;
2. opens the bank login page;
3. reads a product from the shop catalog;
4. gets a CSRF token and registers a temporary shop user;
5. logs the user in over HTTP;
6. adds a product to the cart and creates an order;
7. starts a shop payment authorization;
8. repeats the same request with the same idempotency key;
9. verifies that a second authorization is not created;
10. reads the bank authorization state as `PENDING`.

The smoke test stops before a real demonstration transfer is approved.

Run it locally after both applications are running:

```bash
cd "<PROJECT_WSL>"
bash scripts/e2e-smoke.sh
```

If explicit values are needed:

```bash
BANK_URL=http://127.0.0.1:8080 \
SHOP_URL=http://127.0.0.1:8081 \
BANK_HEALTH_URL=http://127.0.0.1:8082/actuator/health \
SHOP_HEALTH_URL=http://127.0.0.1:8083/actuator/health \
BANK_API_KEY=ci-bank-key BANK_API_KEY_ID=legacy \
bash scripts/e2e-smoke.sh
```

Interpretation:

- `401` usually indicates an API key, profile or endpoint-protection problem;
- `429` indicates rate limiting;
- a timeout usually indicates a stopped process, wrong address or WSL/Windows connectivity problem;
- a shop-to-bank error should be investigated in both application logs.

## 11. Actuator and manual API checks

PowerShell health checks:

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8082/actuator/health
Invoke-WebRequest -UseBasicParsing http://localhost:8083/actuator/health
```

Prometheus endpoints:

```text
http://localhost:8082/actuator/prometheus
http://localhost:8083/actuator/prometheus
```

Metrics may require Basic Authentication in DEV. A `200` response with Prometheus series indicates a working connection. `401` means that credentials are missing or wrong; `404` commonly means a wrong management port, disabled endpoint or wrong profile.

## 12. Development monitoring

Monitoring is a separate container set. Start the bank and shop first, then prepare monitoring:

```bash
cd "<PROJECT_WSL>"
bash scripts/monitoring/monitoring.sh setup
bash scripts/monitoring/monitoring.sh start --profile dev
bash scripts/monitoring/monitoring.sh status --profile dev
```

The script prepares `.novabank/monitoring/.env.monitoring` and Prometheus configuration. Never commit those generated files.

Default monitoring URLs:

```text
Prometheus:   http://localhost:9090
Grafana:      http://localhost:3000
Alertmanager: http://localhost:9093
```

The bank and shop targets should be `UP`. A connection refusal normally means that the application is stopped or its management port is wrong.

## 13. Payments

The shop supports the simulated `bank-demo` provider and Stripe test mode. The two flows have different requirements:

- `bank-demo` uses the bank API and local demonstration accounts;
- Stripe requires test credentials and, for webhook scenarios, Stripe CLI.

Do not use production payment keys. Keep merchant account numbers and provider secrets in local environment files or Vault, never in Git.

## 14. Vault, AppRole and production TLS

The `prod` profile must not start until Vault is available and unsealed.

Example WSL environment:

```bash
export VAULT_ADDR='https://127.0.0.1:8200'
export VAULT_CACERT="<PROJECT_WSL>/.novabank/tls/vault/vault.crt"
unset VAULT_CAPATH
unset VAULT_SKIP_VERIFY
vault status
```

If Vault reports a certificate problem, place the certificate used by the active listener in `.novabank/tls/vault/vault.crt` and compare SHA-256 fingerprints. Do not enable `VAULT_SKIP_VERIFY`.

If Vault is sealed, enter three of the five Shamir unseal keys. The root token is for administration, not daily application startup.

Prepare policies and AppRole through Control Center or the documented Vault procedure. The local bootstrap files are:

```text
.novabank/env/prod.bank.env
.novabank/env/prod.shop.env
```

They may contain Vault address, CA, Role ID, Secret ID, truststore path and truststore password. Keep unseal keys, root token and recovery material outside the repository.

The PROD applications require their own TLS keystores, truststores, secure cookies and IntelliJ configurations. Start them only after Vault status, AppRole authentication and certificate validation succeed.

## 15. Retention, audit and sessions

Technical retention removes only configured auxiliary and audit data. It must not remove accounts, balances, transactions, cards, orders, payments, refunds or data required for financial history.

Start in dry-run mode:

```text
RETENTION_ENABLED=false
RETENTION_DRY_RUN=true
RETENTION_SCHEDULE=0 0 3 * * *
RETENTION_BATCH_SIZE=500
RETENTION_GRACE_PERIOD_DAYS=7
```

Safe production order:

1. approve retention periods;
2. back up both databases;
3. test restoring the backup;
4. run a dry run and review counts;
5. review the audit and report;
6. only then set `RETENTION_DRY_RUN=false`;
7. monitor retention success and failure metrics.

The administrator page is available at `http://localhost:8080/admin/data-retention` and requires an authenticated administrator.

## 16. PostgreSQL backups and disaster recovery

Store backups outside the repository. Before any destructive restore operation:

1. verify the exact backup and target database;
2. stop the dependent application;
3. create a current safety backup;
4. restore into an isolated environment when possible;
5. run migrations and smoke checks;
6. record the result.

Use the backup scripts from `scripts/backup` and test the DR procedure regularly. Do not use a broad delete command or overwrite an unknown database.

## 17. Control Center operations

Control Center provides dependency checks, first-run preparation, environment creation, database startup, application status, logs, Flyway diagnostics, Stripe listener operations, builds, monitoring, E2E smoke testing and selected Vault/TLS procedures.

It does not replace Vault configuration, certificate creation, EnvFile setup or IntelliJ configuration. An operation requiring download or `sudo` asks for confirmation. Pressing Enter at a confirmation prompt cancels the operation.

The local activity log is stored below `.novabank/control-center` and is ignored by Git.

## 18. Git and secret safety

Keep these local files out of commits:

- `.novabank/` and its environment files;
- `.env` files, except safe `.env.example` templates;
- database backups;
- TLS certificates and keystores;
- generated logs and JVM crash reports;
- Maven `target` directories and local caches;
- IDE-specific local configuration.

Before the first commit or push:

```powershell
git status --short
git add -n -A
```

Review the dry-run output. Do not commit API keys, passwords, tokens or local caches.

## 19. Troubleshooting checklist

### Applications do not start

- check that PostgreSQL and Redis are running;
- check that ports `8080`, `8081`, `8082` and `8083` are free;
- verify the active Spring profile and EnvFile;
- inspect the application log;
- restart the Java process after environment changes.

### Integration tests cannot find Docker

- verify `docker version` and `docker info` in the same environment as Maven;
- on Windows, use WSL JDK with WSL Docker or Docker Desktop integration;
- do not run Testcontainers from a Windows JDK that cannot reach the WSL Docker socket.

### E2E waits for health endpoints

- verify bank `8082` and shop `8083` management ports;
- call both health URLs with `curl`;
- check that the applications use the intended profile;
- inspect both application logs.

### E2E returns `401`

Verify `BANK_API_KEY` and `BANK_API_KEY_ID` against the active bank profile. The status endpoint requires the bank API key.

### Shop order returns `503`

Verify that `SHOP_MERCHANT_ACCOUNT_NUMBER` contains a valid 26-digit account number in DEV or in the Vault values used by PROD. The merchant account must be active in the bank for a real authorization flow.

## 20. Minimal startup order

```text
Project path
  -> WSL / Docker / JDK
  -> Control Center first run
  -> project.env
  -> PostgreSQL + Redis
  -> dev/test/prod profile
  -> NovaBank
  -> NovaTech Shop
  -> monitoring
  -> tests / E2E
  -> backups and operational checks
```

For PROD, Vault, TLS, AppRole and certificate checks must complete before the applications start.

## 21. Quick checklists

### Daily DEV

- Docker Engine is running;
- PostgreSQL and Redis are healthy;
- both applications use `dev` and the correct EnvFile;
- bank and shop health endpoints respond;
- monitoring is optional and starts after the applications;
- no `.novabank` file is staged in Git.

### TEST

- Docker is available to Maven/Testcontainers;
- unit tests use `-Punit-tests`;
- integration tests use `-Pintegration-tests verify`;
- bank and shop tests are run separately when diagnosing failures.

### PROD

- Vault is reachable, trusted and unsealed;
- AppRole and TLS material are valid;
- secrets are not in Git;
- correct PROD EnvFiles and IntelliJ configurations are used;
- backups and rollback procedures are available;
- root tokens are not used as application credentials.

The Polish extended edition remains available at [General_Instruction.pl.md](General_Instruction.pl.md). Treat examples as templates and replace all project paths, credentials, IDs and secrets with values from the current installation.
