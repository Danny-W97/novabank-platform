# NovaBank i NovaTech Shop

[English version](README.md)

Edukacyjny projekt demonstracyjny składający się z dwóch aplikacji Spring Boot:

- **NovaBank** — aplikacja bankowa z kontami, przelewami, kartami, walutami, historią operacji, 2FA i panelem administracyjnym;
- **NovaTech Shop** — aplikacja sklepowa z katalogiem produktów, kontem klienta, koszykiem, zamówieniami, płatnościami i refundacjami.

Projekt pokazuje integrację dwóch usług, bezpieczne zarządzanie sesjami i sekretami, migracje bazy danych, testy automatyczne oraz podstawowy monitoring. Nie jest prawdziwym bankiem, operatorem płatności ani gotowym systemem produkcyjnym. Płatności są symulowane przez `bank-demo` albo wykonywane w trybie testowym Stripe.

## Najważniejsze elementy

NovaBank obsługuje między innymi:

- rejestrację, logowanie, zmianę hasła i historię logowań;
- konta bankowe, salda, przelewy zwykłe i zaplanowane oraz zapisanych odbiorców;
- karty demonstracyjne, płatności, refundacje i autoryzację płatności sklepu;
- wymianę walut, limity użytkownika, rate limiting oraz uwierzytelnianie 2FA/TOTP;
- audyt administracyjny, retencję danych technicznych i eksport potwierdzeń do PDF.

NovaTech Shop obsługuje między innymi:

- katalog produktów, konta klientów, adresy dostawy i koszyk;
- zamówienia, płatności przez NovaBank lub Stripe sandbox oraz refundacje;
- uzgadnianie statusu płatności z bankiem i webhooki operatora płatności;
- niezależne uwierzytelnianie klientów sklepu.

## Architektura

```text
                 +----------------------+
                 |      NovaBank        |
                 |      :8080           |
                 +----------+-----------+
                            |
                    API autoryzacji płatności
                            |
                 +----------v-----------+
                 |    NovaTech Shop     |
                 |      :8081           |
                 +----------------------+

  PostgreSQL bank :5300       PostgreSQL shop :5400       Redis :6379
  Mailpit SMTP    :11026      Mailpit WWW     :18026
  Actuator bank   :8082       Actuator shop   :8083
```

Bank korzysta z PostgreSQL, JPA, Flyway, Spring Security i sesji przechowywanych w Redisie. Sklep korzysta z PostgreSQL/JDBC, Flyway, Spring Security oraz sesji JDBC; Redis wspiera między innymi dane pomocnicze i ograniczanie liczby żądań. Profil produkcyjny pobiera sekrety z HashiCorp Vault.

## Technologie

- Java 25;
- Spring Boot 4.1.0;
- Maven Wrapper;
- PostgreSQL 18;
- Redis 7.4-alpine;
- Spring Security, Thymeleaf, JPA/JDBC i Spring Session;
- Flyway, Actuator, Micrometer/Prometheus i Springdoc OpenAPI;
- Testcontainers dla testów integracyjnych;
- Docker Compose dla lokalnej infrastruktury.

## Wymagania

Do pracy lokalnej potrzebne są:

- JDK 25;
- Docker Engine z Docker Compose;
- Git;
- Bash, `curl` i `jq` do przewodnika oraz testów E2E;
- na Windowsie zalecane są WSL 2 i PowerShell.

HashiCorp Vault, Stripe CLI, IntelliJ IDEA, OpenSSL i GPG są potrzebne tylko do wybranych scenariuszy: profilu PROD, TLS, Stripe sandbox, monitoringu lub kopii zapasowych.

## Szybki start

Najprostsza ścieżka korzysta z NovaBank Control Center. Uruchom ją z katalogu projektu.

### Linux, WSL lub macOS

```bash
bash ./scripts/start.sh
```

Panel otwiera się pod `http://127.0.0.1:18110`. Zalecany sposób pracy to WSL/Bash; skrypty Windows pozostają tylko dla zgodności. Stare menu terminalowe: `bash ./scripts/start.sh --cli`.

W **Operacje → Salda DEV** można dodać lub odjąć testowe środki na aktywnych kontach PLN. Każda korekta wymaga wyboru konta, kwoty i potwierdzenia, jest audytowana i chroniona przed powtórzeniem tym samym UUID. TEST/PROD nie udostępniają korekt.

**Powiadomienia** zbiera ostrzeżenia/błędy banku, sklepu i zadań panelu, z licznikiem oraz przewijaną historią. Scheduler sprawdzania płatności w sklepie zawiesza dalsze odpytywanie po trzech kolejnych błędach, bez oznaczania nierozstrzygniętej płatności jako odrzuconej. Po aktualizacji kodu uruchom ponownie panel i obie aplikacje DEV oraz sprawdź historię migracji Flyway. Szczegóły: [Control Center w instrukcji głównej](docs/General_Instruction.pl.md#control-center).

Pierwszy start wykonasz z **Konfiguracja DEV**; zaawansowane ustawienia edytujesz w **Pliki i sekrety**. Panel uruchamia lokalne bazy/Redis, buildy i aplikacje DEV bez zatrzymywania IDE. **Operacje → Vault** obejmuje status, pojedynczy udział Shamir, AppRole oraz rotację/finalizację Secret ID. Dodano bootstrap, buildy PROD i lokalne keystore TLS. Każde zadanie PROD wymaga ostrzeżenia i wpisania PROD; tylko lokalny HTTPS Vault z CA projektu i token operatora, nie root. Panel nie inicjalizuje Vault, nie edytuje biznesowych KV i nie wdraża aplikacji PROD. Jest też przewodnik, raport IntelliJ, ograniczone porządkowanie artefaktów i PL/EN dla nawigacji/nazw operacji. Pełne wymagania: [instrukcja główna](docs/General_Instruction.pl.md#control-center).

Nie dodawaj katalogu `.novabank` do repozytorium. Może zawierać lokalne hasła, klucze, certyfikaty, tokeny i logi.

## Uruchomienie ręczne

1. Użyj `bash ./scripts/start.sh` → **Konfiguracja DEV**. Nie kopiuj mieszanego, historycznego `.env.example` w całości do `project.env`.
2. `project.env` przechowuje niewrażliwe ustawienia infrastruktury, a `dev.env` — hasła, klucze i ustawienia DEV. Alternatywny generator WSL:

   ```bash
   python3 -B scripts/control-center/web/dev_env_layout.py prepare
   ```

3. Uruchom infrastrukturę (PostgreSQL, Redis oraz lokalną skrzynkę Mailpit `bank-mailpit`):

   ```bash
    docker compose --env-file .novabank/env/project.env --env-file .novabank/env/dev.env up -d postgres shop-postgres redis mailpit
    docker compose --env-file .novabank/env/project.env --env-file .novabank/env/dev.env ps
   ```

4. Uruchom obie aplikacje w profilu `dev`, najlepiej przez IntelliJ IDEA albo gotowy skrypt:

   ```powershell
   .\scripts\start-dev.ps1
   ```

   Skrypt zatrzymuje procesy developerskie i uruchamia bank oraz sklep z lokalnym plikiem środowiskowym. Do zatrzymania aplikacji użyj:

   ```powershell
   .\scripts\stop-dev.ps1
   ```

Domyślne adresy:

- bank: <http://localhost:8080/login>;
- panel administratora banku: <http://localhost:8080/admin> (wymaga roli `ADMIN`);
- sklep: <http://localhost:8081/novatech_shop.html>;
- skrzynka pocztowa DEV (Mailpit): <http://localhost:18026>;
- Actuator banku: <http://localhost:8082/actuator/health>;
- Actuator sklepu: <http://localhost:8083/actuator/health>.

Lokalne porty infrastruktury są dostępne tylko na `127.0.0.1`:

- bank PostgreSQL: `5300`;
- shop PostgreSQL: `5400`;
- Redis: `6379`;
- Mailpit SMTP (`bank-mailpit`): `11026`;
- Mailpit WWW (`bank-mailpit`): `18026`.

### Odblokowanie uprawnień administratora

Rejestracja przez formularze www tworzy standardowe konta z rolą `USER`. Aby odblokować panel administratora banku (`/admin`):
1. Zarejestruj konto na `/register` i aktywuj je linkiem ze skrzynki Mailpit (<http://localhost:18026>).
2. Nadaj rolę administratora:
   * **Przez konfigurację**: ustaw `ADMIN_EMAIL=twoj_email@example.test` w `.novabank/env/dev.env` i zrestartuj aplikację banku, **lub**
   * **Bez restartu (SQL w Dockerze)**:
     ```bash
     docker exec -it bank-postgres psql -U bankuser -d bankdb -c "UPDATE users SET role = 'ADMIN' WHERE email = 'twoj_email@example.test';"
     ```
3. Wyloguj się i zaloguj ponownie, po czym otwórz <http://localhost:8080/admin>. Szczegóły: [docs/General_Instruction.pl.md](docs/General_Instruction.pl.md#42-administrator-banku).

## Profile Spring

### `dev`

Profil do codziennej pracy lokalnej. Korzysta z lokalnych baz, Redis, HTTP oraz plików `.novabank/env/*.env`. Nie wymaga Vault ani Stripe.

### `test`

Profil do automatyzacji. Testy integracyjne uruchamiają izolowane PostgreSQL i Redis przez Testcontainers. Nie kieruj testów na bazy używane przez `dev`.

### `prod`

Profil wymagający zewnętrznego zarządzania sekretami, Vault, AppRole, TLS i bezpiecznych cookies. Nie używaj lokalnych sekretów developerskich w środowisku produkcyjnym.

## Testy i budowanie

Testy są podzielone według konwencji Maven:

- `Unit-test` — klasy zakończone `*Test.java`. Są szybkie, korzystają z mocków lub testów warstwy MVC i nie wymagają uruchomionego Dockera.
- `Integration-test` — klasy zakończone `*IT.java`. Uruchamiają pełniejszy kontekst aplikacji, Testcontainers albo przepływ HTTP i są wykonywane przez Failsafe.

Testy jednostkowe banku:

```powershell
.\mvnw.cmd -Punit-tests test
```

Testy jednostkowe sklepu:

```powershell
.\mvnw.cmd -f shop-app\pom.xml -Punit-tests test
```

Testy integracyjne wymagają działającego Docker Engine:

```powershell
.\mvnw.cmd -Pintegration-tests verify
.\mvnw.cmd -f shop-app\pom.xml -Pintegration-tests verify
```

Odpowiedniki dla Linux/WSL:

```bash
./mvnw -Punit-tests test
./mvnw -f shop-app/pom.xml -Punit-tests test
./mvnw -Pintegration-tests verify
./mvnw -f shop-app/pom.xml -Pintegration-tests verify
```

Test HTTP E2E znajduje się w [scripts/e2e-smoke.sh](scripts/e2e-smoke.sh). Sprawdza między innymi rejestrację klienta sklepu, logowanie, koszyk, utworzenie zamówienia oraz przepływ płatności między sklepem a bankiem.

Pipeline GitHub Actions w [.github/workflows/ci.yml](.github/workflows/ci.yml) uruchamia testy jednostkowe, integracyjne, przepływ HTTP E2E oraz budowanie artefaktów JAR.

## Konfiguracja sekretów

`.env.example` jest wyłącznie szablonem. Nie wpisuj do repozytorium:

- haseł baz danych;
- `BANK_API_KEY`, `BANK_API_KEYS` ani kluczy Stripe;
- `TOTP_ENCRYPTION_KEY`;
- danych AppRole Vault;
- haseł i plików keystore/truststore;
- lokalnych backupów, certyfikatów i logów.

W profilu `dev` sekrety są przechowywane lokalnie w `.novabank`. W profilu `prod` powinny pochodzić z Vault, Kubernetes Secret albo innego menedżera sekretów.

## Monitoring

Projekt zawiera konfigurację Prometheus, Grafany i Alertmanagera. Monitoring developerski można przygotować skryptem:

```bash
bash scripts/monitoring/monitoring.sh setup
bash scripts/monitoring/monitoring.sh start
bash scripts/monitoring/monitoring.sh status
```

Konfiguracja monitoringu wymaga osobnego pliku `.novabank/monitoring/.env.monitoring`, tworzonego na podstawie [infra/monitoring/.env.monitoring.example](infra/monitoring/.env.monitoring.example). Endpointy Actuator nie powinny być publicznie wystawiane bez dodatkowego uwierzytelniania i ograniczenia dostępu.

## Dokumentacja

Pełny podręcznik obsługi całej platformy — **NovaBank, NovaTech Shop i Control Center** — jest utrzymywany w dwóch równoważnych wersjach językowych:

- [General_Instruction.pl.md — polska](docs/General_Instruction.pl.md);
- [General_Instruction.md — angielska](docs/General_Instruction.md).

Są to jedyne dokumenty w `docs`. Opisują pierwszy start, profile DEV/TEST/PROD, obsługę banku i sklepu, operacje Control Center i pola ich formularzy, płatności/Stripe TEST, Vault/TOTP/TLS, monitoring, kopie/odtwarzanie/DR, testowanie i rozwiązywanie problemów. Przed operacją administracyjną zacznij od instrukcji — wyjaśnia wymagania, słowa potwierdzenia i zależności między krokami.

Opcjonalne wizualne wprowadzenie jest zasobem aplikacji w `scripts/control-center/guide`, uruchamianym przez `bash scripts/control-center/interactive-guide.sh`; uzupełnia podręcznik, a nie go zastępuje.

## Bezpieczeństwo i zakres projektu

Projekt służy do nauki i demonstracji. Nie używaj go do przechowywania prawdziwych środków, danych klientów, numerów kart, haseł produkcyjnych ani rzeczywistych danych osobowych. Przed użyciem publicznym lub produkcyjnym potrzebne byłyby dodatkowe audyty bezpieczeństwa, testy obciążeniowe, przegląd zgodności prawnej, hardening infrastruktury i niezależna weryfikacja procesu płatności.

## Licencja

Projekt jest udostępniany na licencji MIT. Szczegóły znajdują się w pliku [LICENSE](LICENSE).
