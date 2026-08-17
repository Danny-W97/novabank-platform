#!/usr/bin/env bash
set -uo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOCAL_ROOT="$PROJECT_ROOT/.novabank"
MAIN_GUIDE_REL="docs/General_Instruction.md"
ENV_DIR="$LOCAL_ROOT/env"
MONITORING_DIR="$LOCAL_ROOT/monitoring"
TLS_DIR="$LOCAL_ROOT/tls"
VAULT_TLS_DIR="$TLS_DIR/vault"
PROD_TLS_DIR="$TLS_DIR/prod"
ACTIVITY_DIR="$LOCAL_ROOT/control-center"
ACTIVITY_LOG="$ACTIVITY_DIR/activity.log"
LANGUAGE_CONFIG="$ACTIVITY_DIR/settings.conf"
LANGUAGE="${CONTROL_CENTER_LANGUAGE:-}"
PROJECT_ENV="$ENV_DIR/project.env"
DEV_ENV="$ENV_DIR/dev.env"
PROD_BANK_ENV="$ENV_DIR/prod.bank.env"
PROD_SHOP_ENV="$ENV_DIR/prod.shop.env"
MONITORING_ENV="$MONITORING_DIR/.env.monitoring"
PROMETHEUS_CONFIG="$MONITORING_DIR/prometheus.yml"
VAULT_CERT="$VAULT_TLS_DIR/vault.crt"
TRUSTSTORE="$VAULT_TLS_DIR/vault-truststore.p12"
BANK_KEYSTORE="$PROD_TLS_DIR/bank-server.p12"
SHOP_KEYSTORE="$PROD_TLS_DIR/shop-server.p12"
PDF_VENV="$LOCAL_ROOT/pdf-venv"

migrate_runtime_file() {
  local old_path="$1"
  local new_path="$2"
  [[ -e "$old_path" ]] || return 0
  if [[ -e "$new_path" ]]; then
    return 0
  fi
  mkdir -p "$(dirname "$new_path")"
  mv "$old_path" "$new_path"
}

migrate_runtime_layout() {
  mkdir -p "$ENV_DIR" "$MONITORING_DIR" "$VAULT_TLS_DIR" "$PROD_TLS_DIR" "$ACTIVITY_DIR"

  migrate_runtime_file "$PROJECT_ROOT/.env" "$PROJECT_ENV"
  migrate_runtime_file "$PROJECT_ROOT/scripts/.env.dev" "$DEV_ENV"
  migrate_runtime_file "$PROJECT_ROOT/scripts/.env.prod.bank" "$PROD_BANK_ENV"
  migrate_runtime_file "$PROJECT_ROOT/scripts/.env.prod.shop" "$PROD_SHOP_ENV"
  migrate_runtime_file "$PROJECT_ROOT/infra/monitoring/.env.monitoring" "$MONITORING_ENV"
  migrate_runtime_file "$PROJECT_ROOT/infra/monitoring/prometheus/prometheus.yml" "$PROMETHEUS_CONFIG"
  migrate_runtime_file "$PROJECT_ROOT/infra/vault/tls/vault.crt" "$VAULT_CERT"
  migrate_runtime_file "$PROJECT_ROOT/infra/vault/tls/vault-truststore.p12" "$TRUSTSTORE"
  migrate_runtime_file "$PROJECT_ROOT/infra/prod/tls/bank-server.p12" "$BANK_KEYSTORE"
  migrate_runtime_file "$PROJECT_ROOT/infra/prod/tls/shop-server.p12" "$SHOP_KEYSTORE"
  migrate_runtime_file "$PROJECT_ROOT/.venv-pdf" "$PDF_VENV"
}

migrate_runtime_layout

load_language() {
  local configured="${CONTROL_CENTER_LANGUAGE:-}"

  if [[ -z "$configured" && -f "$LANGUAGE_CONFIG" ]]; then
    configured="$(awk -F= '$1 == "language" { print $2; exit }' "$LANGUAGE_CONFIG" 2>/dev/null || true)"
  fi

  case "$configured" in
    pl|en) LANGUAGE="$configured" ;;
    *) LANGUAGE="pl" ;;
  esac
}

save_language() {
  local language="$1"
  local temp

  temp="$(mktemp "${LANGUAGE_CONFIG}.tmp.XXXXXX")" || return 1
  if ! printf 'language=%s\n' "$language" > "$temp"; then
    rm -f "$temp"
    return 1
  fi
  if ! mv "$temp" "$LANGUAGE_CONFIG"; then
    rm -f "$temp"
    return 1
  fi
  chmod 600 "$LANGUAGE_CONFIG" 2>/dev/null || true
}

set_language() {
  case "$1" in
    pl|en)
      LANGUAGE="$1"
      save_language "$LANGUAGE"
      ;;
    *)
      return 1
      ;;
  esac
}

is_english() {
  [[ "$LANGUAGE" == "en" ]]
}

load_language

t() {
  local key="$1"

  case "$LANGUAGE:$key" in
    pl:common.project) printf 'Projekt:' ;;
    en:common.project) printf 'Project:' ;;
    pl:common.choice) printf 'Wybór:' ;;
    en:common.choice) printf 'Choice:' ;;
    pl:common.back) printf 'Powrót' ;;
    en:common.back) printf 'Back' ;;
    pl:common.exit) printf 'Wyjście' ;;
    en:common.exit) printf 'Exit' ;;
    pl:common.invalid) printf 'Nieprawidłowy wybór.' ;;
    en:common.invalid) printf 'Invalid choice.' ;;
    pl:common.pause) printf 'Naciśnij Enter, aby wrócić do menu, albo wpisz exit: ' ;;
    en:common.pause) printf 'Press Enter to return to the menu, or type exit: ' ;;
    pl:common.command) printf 'Polecenie:' ;;
    en:common.command) printf 'Command:' ;;
    pl:common.instruction) printf 'Instrukcja:' ;;
    en:common.instruction) printf 'Guide:' ;;
    pl:common.run_operation) printf 'Uruchomić operację?' ;;
    en:common.run_operation) printf 'Run operation?' ;;
    pl:common.cancelled) printf 'Anulowano.' ;;
    en:common.cancelled) printf 'Cancelled.' ;;
    pl:common.success) printf 'Operacja zakończona poprawnie.' ;;
    en:common.success) printf 'Operation completed successfully.' ;;
    pl:common.failure) printf 'Operacja zakończyła się błędem (kod %s).' ;;
    en:common.failure) printf 'Operation failed (code %s).' ;;
    pl:common.activity_log) printf 'Dziennik centrali:' ;;
    en:common.activity_log) printf 'Control Center log:' ;;
    pl:common.prod_warning) printf 'To jest operacja dla profilu PROD.' ;;
    en:common.prod_warning) printf 'This operation targets the PROD profile.' ;;
    pl:common.prod_confirm) printf 'Wpisz PROD, aby kontynuować: ' ;;
    en:common.prod_confirm) printf 'Type PROD to continue: ' ;;
    pl:common.language_saved) printf 'Język został zapisany.' ;;
    en:common.language_saved) printf 'Language saved.' ;;
    pl:common.language_error) printf 'Nie udało się zapisać ustawienia języka.' ;;
    en:common.language_error) printf 'Unable to save the language setting.' ;;
    pl:common.current_language) printf 'Bieżący język:' ;;
    en:common.current_language) printf 'Current language:' ;;
    pl:common.language) printf 'Język' ;;
    en:common.language) printf 'Language' ;;
    pl:common.polish) printf 'Język polski' ;;
    en:common.polish) printf 'Polish' ;;
    pl:common.english) printf 'Język angielski' ;;
    en:common.english) printf 'English' ;;
    pl:common.default) printf 'domyślny' ;;
    en:common.default) printf 'default' ;;
    pl:settings.title) printf 'Ustawienia' ;;
    en:settings.title) printf 'Settings' ;;
    pl:settings.language_option) printf 'Język' ;;
    en:settings.language_option) printf 'Language' ;;
    pl:settings.polish_option) printf 'Język polski (domyślny)' ;;
    en:settings.polish_option) printf 'Polish (default)' ;;
    pl:settings.english_option) printf 'Język angielski' ;;
    en:settings.english_option) printf 'English' ;;

    pl:main.1) printf 'Pierwsze uruchomienie' ;;
    en:main.1) printf 'First startup' ;;
    pl:main.2) printf 'Środowiska i aplikacje' ;;
    en:main.2) printf 'Environments and applications' ;;
    pl:main.3) printf 'Instalacja i wymagania' ;;
    en:main.3) printf 'Installation and requirements' ;;
    pl:main.4) printf 'Monitoring' ;;
    en:main.4) printf 'Monitoring' ;;
    pl:main.5) printf 'Kopie zapasowe PostgreSQL' ;;
    en:main.5) printf 'PostgreSQL backups' ;;
    pl:main.6) printf 'Disaster Recovery' ;;
    en:main.6) printf 'Disaster Recovery' ;;
    pl:main.7) printf 'Vault i AppRole' ;;
    en:main.7) printf 'Vault and AppRole' ;;
    pl:main.8) printf 'TLS i dokumenty generowane' ;;
    en:main.8) printf 'TLS and generated documents' ;;
    pl:main.9) printf 'Testy aplikacji' ;;
    en:main.9) printf 'Application tests' ;;
    pl:main.10) printf 'Instrukcje' ;;
    en:main.10) printf 'Documentation' ;;
    pl:main.11) printf 'Interaktywne wprowadzenie do projektu' ;;
    en:main.11) printf 'Interactive project guide' ;;
    pl:main.12) printf 'Diagnostyka całego środowiska' ;;
    en:main.12) printf 'Full environment diagnostics' ;;
    pl:main.13) printf 'Utrzymanie aplikacji' ;;
    en:main.13) printf 'Application maintenance' ;;
    pl:main.14) printf 'Ustawienia' ;;
    en:main.14) printf 'Settings' ;;

    pl:first.title) printf 'Pierwsze uruchomienie' ;;
    en:first.title) printf 'First startup' ;;
    pl:first.1) printf 'Sprawdź kompletność pierwszego uruchomienia' ;;
    en:first.1) printf 'Check first-startup readiness' ;;
    pl:first.2) printf 'Utwórz lub uzupełnij .novabank/env/project.env' ;;
    en:first.2) printf 'Create or update .novabank/env/project.env' ;;
    pl:first.3) printf 'Utwórz .novabank/env/dev.env' ;;
    en:first.3) printf 'Create .novabank/env/dev.env' ;;
    pl:first.4) printf 'Utwórz .novabank/env/prod.bank.env i .novabank/env/prod.shop.env' ;;
    en:first.4) printf 'Create .novabank/env/prod.bank.env and .novabank/env/prod.shop.env' ;;
    pl:first.5) printf 'Pokaż mapę sekretów PROD i miejsc zapisu' ;;
    en:first.5) printf 'Show the PROD secrets map and storage locations' ;;
    pl:first.6) printf 'Przygotuj konfigurację monitoringu DEV' ;;
    en:first.6) printf 'Prepare DEV monitoring configuration' ;;
    pl:first.7) printf 'Sprawdź wymagane komponenty' ;;
    en:first.7) printf 'Check required components' ;;
    pl:first.8) printf 'Otwórz instrukcję pierwszego uruchomienia' ;;
    en:first.8) printf 'Open the first-startup guide' ;;

    pl:maintenance.title) printf 'Utrzymanie aplikacji' ;;
    en:maintenance.title) printf 'Application maintenance' ;;
    pl:maintenance.1) printf 'Docker, PostgreSQL i Redis' ;;
    en:maintenance.1) printf 'Docker, PostgreSQL and Redis' ;;
    pl:maintenance.2) printf 'Diagnostyka Flyway' ;;
    en:maintenance.2) printf 'Flyway diagnostics' ;;
    pl:maintenance.3) printf 'Stripe CLI' ;;
    en:maintenance.3) printf 'Stripe CLI' ;;
    pl:maintenance.4) printf 'Budowanie aplikacji' ;;
    en:maintenance.4) printf 'Build applications' ;;
    pl:maintenance.5) printf 'Sesje Redis' ;;
    en:maintenance.5) printf 'Redis sessions' ;;
    pl:maintenance.6) printf 'Porządkowanie artefaktów lokalnych' ;;
    en:maintenance.6) printf 'Clean up local artifacts' ;;

    pl:docker.title) printf 'Docker, PostgreSQL i Redis' ;;
    en:docker.title) printf 'Docker, PostgreSQL and Redis' ;;
    pl:docker.1) printf 'Pokaż status kontenerów i healthchecków' ;;
    en:docker.1) printf 'Show container and healthcheck status' ;;
    pl:docker.2) printf 'Uruchom PostgreSQL i Redis' ;;
    en:docker.2) printf 'Start PostgreSQL and Redis' ;;
    pl:docker.3) printf 'Zrestartuj PostgreSQL i Redis' ;;
    en:docker.3) printf 'Restart PostgreSQL and Redis' ;;
    pl:docker.4) printf 'Zatrzymaj PostgreSQL i Redis' ;;
    en:docker.4) printf 'Stop PostgreSQL and Redis' ;;
    pl:docker.5) printf 'Pokaż logi PostgreSQL i Redis' ;;
    en:docker.5) printf 'Show PostgreSQL and Redis logs' ;;
    pl:docker.6) printf 'Sprawdź konfigurację Compose' ;;
    en:docker.6) printf 'Validate Compose configuration' ;;

    pl:stripe.title) printf 'Stripe CLI' ;;
    en:stripe.title) printf 'Stripe CLI' ;;
    pl:stripe.1) printf 'Pokaż status i konfigurację Stripe CLI' ;;
    en:stripe.1) printf 'Show Stripe CLI status and configuration' ;;
    pl:stripe.2) printf 'Zaloguj Stripe CLI' ;;
    en:stripe.2) printf 'Log in to Stripe CLI' ;;
    pl:stripe.3) printf 'Uruchom listener webhooków DEV' ;;
    en:stripe.3) printf 'Start the DEV webhook listener' ;;

    pl:build.title) printf 'Budowanie aplikacji' ;;
    en:build.title) printf 'Build applications' ;;
    pl:build.1) printf 'Zbuduj bank z testami jednostkowymi' ;;
    en:build.1) printf 'Build bank with unit tests' ;;
    pl:build.2) printf 'Zbuduj shop-app z testami jednostkowymi' ;;
    en:build.2) printf 'Build shop-app with unit tests' ;;
    pl:build.3) printf 'Zbuduj bank i shop-app' ;;
    en:build.3) printf 'Build bank and shop-app' ;;
    pl:build.4) printf 'Zbuduj artefakty PROD bez testów' ;;
    en:build.4) printf 'Build PROD artifacts without tests' ;;

    pl:redis.title) printf 'Sesje Redis' ;;
    en:redis.title) printf 'Redis sessions' ;;
    pl:redis.1) printf 'Pokaż status Redis i liczbę sesji banku' ;;
    en:redis.1) printf 'Show Redis status and bank session count' ;;
    pl:redis.2) printf 'Unieważnij wszystkie sesje banku' ;;
    en:redis.2) printf 'Invalidate all bank sessions' ;;

    pl:installation.title) printf 'Instalacja i wymagania' ;;
    en:installation.title) printf 'Installation and requirements' ;;
    pl:installation.1) printf 'Sprawdź kompletność środowiska' ;;
    en:installation.1) printf 'Check environment readiness' ;;
    pl:installation.2) printf 'Zainstaluj Vault w WSL' ;;
    en:installation.2) printf 'Install Vault in WSL' ;;
    pl:installation.3) printf 'Zainstaluj narzędzia WSL: jq, OpenSSL, GPG' ;;
    en:installation.3) printf 'Install WSL tools: jq, OpenSSL, GPG' ;;
    pl:installation.4) printf 'Sprawdź Docker Engine i Compose' ;;
    en:installation.4) printf 'Check Docker Engine and Compose' ;;
    pl:installation.5) printf 'Sprawdź Java/JDK i keytool' ;;
    en:installation.5) printf 'Check Java/JDK and keytool' ;;
    pl:installation.6) printf 'Zainstaluj Stripe CLI (opcjonalnie)' ;;
    en:installation.6) printf 'Install Stripe CLI (optional)' ;;
    pl:installation.7) printf 'Uruchom PostgreSQL 18 i Redis 7.4-alpine przez Compose' ;;
    en:installation.7) printf 'Start PostgreSQL 18 and Redis 7.4-alpine with Compose' ;;
    pl:installation.8) printf 'Sprawdź wszystkie zależności projektu' ;;
    en:installation.8) printf 'Check all project dependencies' ;;

    pl:environment.title) printf 'Środowiska i aplikacje' ;;
    en:environment.title) printf 'Environments and applications' ;;
    pl:environment.1) printf 'Przygotuj DEV (env + PostgreSQL + Redis)' ;;
    en:environment.1) printf 'Prepare DEV (env + PostgreSQL + Redis)' ;;
    pl:environment.2) printf 'Rotuj lokalny BANK_API_KEY w DEV' ;;
    en:environment.2) printf 'Rotate the local BANK_API_KEY in DEV' ;;
    pl:environment.3) printf 'Pokaż status infrastruktury i aplikacji' ;;
    en:environment.3) printf 'Show infrastructure and application status' ;;
    pl:environment.4) printf 'Uruchom DEV poza IntelliJ' ;;
    en:environment.4) printf 'Start DEV outside IntelliJ' ;;
    pl:environment.5) printf 'Zatrzymaj DEV uruchomiony poza IntelliJ' ;;
    en:environment.5) printf 'Stop DEV running outside IntelliJ' ;;
    pl:environment.6) printf 'Sprawdź i przygotuj PROD' ;;
    en:environment.6) printf 'Check and prepare PROD' ;;
    pl:environment.7) printf 'Pokaż gotowe konfiguracje IntelliJ' ;;
    en:environment.7) printf 'Show ready IntelliJ configurations' ;;

    pl:monitoring.title) printf 'Monitoring' ;;
    en:monitoring.title) printf 'Monitoring' ;;
    pl:monitoring.1) printf 'Przygotuj konfigurację monitoringu DEV' ;;
    en:monitoring.1) printf 'Prepare DEV monitoring configuration' ;;
    pl:monitoring.2) printf 'Uruchom monitoring i sprawdź Targets=UP' ;;
    en:monitoring.2) printf 'Start monitoring and check Targets=UP' ;;
    pl:monitoring.3) printf 'Pokaż status monitoringu' ;;
    en:monitoring.3) printf 'Show monitoring status' ;;
    pl:monitoring.4) printf 'Logi Prometheus' ;;
    en:monitoring.4) printf 'Prometheus logs' ;;
    pl:monitoring.5) printf 'Logi Grafana' ;;
    en:monitoring.5) printf 'Grafana logs' ;;
    pl:monitoring.6) printf 'Logi Alertmanager' ;;
    en:monitoring.6) printf 'Alertmanager logs' ;;
    pl:monitoring.7) printf 'Zatrzymaj monitoring' ;;
    en:monitoring.7) printf 'Stop monitoring' ;;
    pl:monitoring.8) printf 'Rotuj lokalne hasła monitoringu' ;;
    en:monitoring.8) printf 'Rotate local monitoring passwords' ;;

    pl:backup.title) printf 'Kopie zapasowe PostgreSQL' ;;
    en:backup.title) printf 'PostgreSQL backups' ;;
    pl:backup.1) printf 'Utwórz kompletną kopię obu baz' ;;
    en:backup.1) printf 'Create a full backup of both databases' ;;
    pl:backup.2) printf 'Testowo odtwórz najnowszą kopię' ;;
    en:backup.2) printf 'Test-restore the latest backup' ;;
    pl:backup.3) printf 'Utwórz kopię i zastosuj retencję' ;;
    en:backup.3) printf 'Create a backup and apply retention' ;;
    pl:backup.4) printf 'Zainstaluj dzienny harmonogram' ;;
    en:backup.4) printf 'Install the daily schedule' ;;
    pl:backup.5) printf 'Pokaż stan harmonogramu' ;;
    en:backup.5) printf 'Show schedule status' ;;
    pl:backup.6) printf 'Uruchom harmonogram teraz' ;;
    en:backup.6) printf 'Run the schedule now' ;;
    pl:backup.7) printf 'Pokaż logi harmonogramu' ;;
    en:backup.7) printf 'Show schedule logs' ;;
    pl:backup.8) printf 'Usuń harmonogram' ;;
    en:backup.8) printf 'Remove the schedule' ;;

    pl:dr.title) printf 'Disaster Recovery' ;;
    en:dr.title) printf 'Disaster Recovery' ;;
    pl:dr.1) printf 'Uruchom pełny izolowany test DR' ;;
    en:dr.1) printf 'Run a full isolated DR test' ;;
    pl:dr.2) printf 'Zainstaluj cotygodniowy harmonogram testu DR' ;;
    en:dr.2) printf 'Install the weekly DR test schedule' ;;
    pl:dr.3) printf 'Pokaż stan harmonogramu DR' ;;
    en:dr.3) printf 'Show DR schedule status' ;;
    pl:dr.4) printf 'Uruchom zaplanowany test DR teraz' ;;
    en:dr.4) printf 'Run the scheduled DR test now' ;;
    pl:dr.5) printf 'Pokaż ostatni raport DR' ;;
    en:dr.5) printf 'Show the latest DR report' ;;
    pl:dr.6) printf 'Pokaż logi testów DR' ;;
    en:dr.6) printf 'Show DR test logs' ;;
    pl:dr.7) printf 'Usuń harmonogram testu DR' ;;
    en:dr.7) printf 'Remove the DR test schedule' ;;

    pl:vault.title) printf 'Vault i AppRole' ;;
    en:vault.title) printf 'Vault and AppRole' ;;
    pl:vault.1) printf 'Pokaż status Vault' ;;
    en:vault.1) printf 'Show Vault status' ;;
    pl:vault.2) printf 'Odpieczętuj Vault trzema kluczami' ;;
    en:vault.2) printf 'Unseal Vault with three keys' ;;
    pl:vault.3) printf 'Skonfiguruj polityki i AppRole PROD' ;;
    en:vault.3) printf 'Configure PROD policies and AppRole' ;;
    pl:vault.4) printf 'Rotuj Secret ID banku' ;;
    en:vault.4) printf 'Rotate the bank Secret ID' ;;
    pl:vault.5) printf 'Rotuj Secret ID sklepu' ;;
    en:vault.5) printf 'Rotate the shop Secret ID' ;;
    pl:vault.6) printf 'Rotuj Secret ID obu aplikacji' ;;
    en:vault.6) printf 'Rotate both application Secret IDs' ;;
    pl:vault.7) printf 'Finalizuj rotację banku' ;;
    en:vault.7) printf 'Finalize the bank rotation' ;;
    pl:vault.8) printf 'Finalizuj rotację sklepu' ;;
    en:vault.8) printf 'Finalize the shop rotation' ;;
    pl:vault.9) printf 'Finalizuj rotację obu aplikacji' ;;
    en:vault.9) printf 'Finalize both application rotations' ;;

    pl:tools.title) printf 'TLS i dokumenty generowane' ;;
    en:tools.title) printf 'TLS and generated documents' ;;
    pl:tools.1) printf 'Generuj keystore TLS dla PROD' ;;
    en:tools.1) printf 'Generate TLS keystore for PROD' ;;
    pl:tools.2) printf 'Wymuś ponowne wygenerowanie keystore TLS' ;;
    en:tools.2) printf 'Force-regenerate the TLS keystore' ;;
    pl:tools.3) printf 'Generuj PDF regulaminu' ;;
    en:tools.3) printf 'Generate the terms PDF' ;;

    pl:tests.title) printf 'Testy' ;;
    en:tests.title) printf 'Tests' ;;
    pl:tests.1) printf 'Testy jednostkowe banku' ;;
    en:tests.1) printf 'Bank unit tests' ;;
    pl:tests.2) printf 'Testy jednostkowe sklepu' ;;
    en:tests.2) printf 'Shop unit tests' ;;
    pl:tests.3) printf 'Testy integracyjne banku' ;;
    en:tests.3) printf 'Bank integration tests' ;;
    pl:tests.4) printf 'Testy integracyjne sklepu' ;;
    en:tests.4) printf 'Shop integration tests' ;;
    pl:tests.5) printf 'Smoke E2E bank-sklep' ;;
    en:tests.5) printf 'Bank-shop E2E smoke test' ;;
    pl:tests.6) printf 'Komplet testów jednostkowych obu aplikacji' ;;
    en:tests.6) printf 'All unit tests for both applications' ;;

    pl:docs.title) printf 'Instrukcje' ;;
    en:docs.title) printf 'Documentation' ;;
    pl:docs.1) printf 'Generalna instrukcja aplikacji' ;;
    en:docs.1) printf 'General application guide' ;;
    pl:docs.2) printf 'Interaktywne wprowadzenie do projektu' ;;
    en:docs.2) printf 'Interactive project guide' ;;
    *) printf '%s' "$key" ;;
  esac
}

localized_action_title() {
  local title="$1"

  if [[ "$LANGUAGE" != "en" ]]; then
    printf '%s' "$title"
    return
  fi

  case "$title" in
    "Kontrola pierwszego uruchomienia") printf 'First-startup check' ;;
    "Utworzenie .novabank/env/project.env") printf 'Create .novabank/env/project.env' ;;
    "Utworzenie .novabank/env/dev.env") printf 'Create .novabank/env/dev.env' ;;
    "Utworzenie plików bootstrap PROD") printf 'Create PROD bootstrap files' ;;
    "Mapa sekretów PROD") printf 'PROD secrets map' ;;
    "Przygotowanie monitoringu DEV"|"Konfiguracja monitoringu DEV") printf 'Prepare DEV monitoring' ;;
    "Kontrola wymaganych komponentów"|"Kontrola wymaganych komponentow") printf 'Required components check' ;;
    "Instrukcja pierwszego uruchomienia") printf 'First-startup guide' ;;
    "Status Docker, PostgreSQL i Redis") printf 'Docker, PostgreSQL and Redis status' ;;
    "Start PostgreSQL i Redis") printf 'Start PostgreSQL and Redis' ;;
    "Restart PostgreSQL i Redis") printf 'Restart PostgreSQL and Redis' ;;
    "Stop PostgreSQL i Redis") printf 'Stop PostgreSQL and Redis' ;;
    "Logi PostgreSQL i Redis") printf 'PostgreSQL and Redis logs' ;;
    "Walidacja docker-compose.yaml") printf 'Validate docker-compose.yaml' ;;
    "Diagnostyka migracji Flyway") printf 'Flyway migration diagnostics' ;;
    "Status Stripe CLI") printf 'Stripe CLI status' ;;
    "Logowanie Stripe CLI") printf 'Stripe CLI login' ;;
    "Listener webhookow Stripe") printf 'Stripe webhook listener' ;;
    "Build banku") printf 'Build bank' ;;
    "Build shop-app") printf 'Build shop-app' ;;
    "Build banku i shop-app") printf 'Build bank and shop-app' ;;
    "Build artefaktow PROD") printf 'Build PROD artifacts' ;;
    "Status sesji Redis") printf 'Redis session status' ;;
    "Uniewaznienie sesji banku") printf 'Invalidate bank sessions' ;;
    "Porzadkowanie artefaktow lokalnych") printf 'Clean up local artifacts' ;;
    "Instalacja Vault w WSL") printf 'Install Vault in WSL' ;;
    "Instalacja narzedzi WSL") printf 'Install WSL tools' ;;
    "Kontrola Docker Engine i Compose") printf 'Docker Engine and Compose check' ;;
    "Kontrola Java JDK i keytool") printf 'Java JDK and keytool check' ;;
    "Instalacja Stripe CLI") printf 'Install Stripe CLI' ;;
    "Uruchomienie PostgreSQL i Redis") printf 'Start PostgreSQL and Redis' ;;
    "Pelna kontrola zaleznosci") printf 'Full dependency check' ;;
    "Przygotowanie DEV") printf 'Prepare DEV' ;;
    "Rotacja lokalnego klucza API") printf 'Rotate local API key' ;;
    "Status infrastruktury") printf 'Infrastructure status' ;;
    "Start DEV poza IntelliJ") printf 'Start DEV outside IntelliJ' ;;
    "Stop DEV poza IntelliJ") printf 'Stop DEV outside IntelliJ' ;;
    "Przygotowanie PROD") printf 'Prepare PROD' ;;
    "Konfiguracje IntelliJ") printf 'IntelliJ configurations' ;;
    "Start monitoringu") printf 'Start monitoring' ;;
    "Status monitoringu") printf 'Monitoring status' ;;
    "Logi Prometheus") printf 'Prometheus logs' ;;
    "Logi Grafana") printf 'Grafana logs' ;;
    "Logi Alertmanager") printf 'Alertmanager logs' ;;
    "Stop monitoringu") printf 'Stop monitoring' ;;
    "Rotacja hasel monitoringu") printf 'Rotate monitoring passwords' ;;
    "Backup banku i sklepu") printf 'Bank and shop backup' ;;
    "Test odtworzenia najnowszej kopii") printf 'Latest-backup restore test' ;;
    "Backup z retencja") printf 'Backup with retention' ;;
    "Instalacja harmonogramu backupu") printf 'Install backup schedule' ;;
    "Status harmonogramu backupu") printf 'Backup schedule status' ;;
    "Natychmiastowy zaplanowany backup") printf 'Run scheduled backup now' ;;
    "Logi harmonogramu backupu") printf 'Backup schedule logs' ;;
    "Usuniecie harmonogramu backupu") printf 'Remove backup schedule' ;;
    "Pelny test Disaster Recovery") printf 'Full Disaster Recovery test' ;;
    "Instalacja harmonogramu DR") printf 'Install DR schedule' ;;
    "Status harmonogramu DR") printf 'DR schedule status' ;;
    "Natychmiastowy test DR") printf 'Run DR test now' ;;
    "Ostatni raport DR") printf 'Latest DR report' ;;
    "Logi testow DR") printf 'DR test logs' ;;
    "Usuniecie harmonogramu testu DR") printf 'Remove DR test schedule' ;;
    "Status Vault") printf 'Vault status' ;;
    "Odpieczetowanie Vault") printf 'Unseal Vault' ;;
    "Konfiguracja AppRole PROD") printf 'Configure PROD AppRole' ;;
    "Rotacja Secret ID banku") printf 'Rotate bank Secret ID' ;;
    "Rotacja Secret ID sklepu") printf 'Rotate shop Secret ID' ;;
    "Rotacja Secret ID obu aplikacji") printf 'Rotate both application Secret IDs' ;;
    "Finalizacja rotacji banku") printf 'Finalize bank rotation' ;;
    "Finalizacja rotacji sklepu") printf 'Finalize shop rotation' ;;
    "Finalizacja rotacji obu aplikacji") printf 'Finalize both application rotations' ;;
    "Generowanie keystore TLS") printf 'Generate TLS keystore' ;;
    "Ponowne generowanie keystore TLS") printf 'Regenerate TLS keystore' ;;
    "Generowanie PDF regulaminu") printf 'Generate terms PDF' ;;
    "Testy jednostkowe banku") printf 'Bank unit tests' ;;
    "Testy jednostkowe sklepu") printf 'Shop unit tests' ;;
    "Testy integracyjne banku") printf 'Bank integration tests' ;;
    "Testy integracyjne sklepu") printf 'Shop integration tests' ;;
    "Smoke E2E bank-sklep") printf 'Bank-shop E2E smoke test' ;;
    "Testy jednostkowe banku i sklepu") printf 'Bank and shop unit tests' ;;
    "Generalna instrukcja") printf 'General guide' ;;
    "Interaktywne wprowadzenie") printf 'Interactive guide' ;;
    "Diagnostyka calego srodowiska") printf 'Full environment diagnostics' ;;
    *) printf '%s' "$title" ;;
  esac
}

if [[ -t 1 ]]; then
  C_BLUE=$'\033[1;34m'
  C_GREEN=$'\033[1;32m'
  C_YELLOW=$'\033[1;33m'
  C_RED=$'\033[1;31m'
  C_DIM=$'\033[2m'
  C_RESET=$'\033[0m'
else
  C_BLUE=""
  C_GREEN=""
  C_YELLOW=""
  C_RED=""
  C_DIM=""
  C_RESET=""
fi

is_wsl() {
  [[ -n "${WSL_DISTRO_NAME:-}" ]] || grep -qi microsoft /proc/version 2>/dev/null
}

is_macos() {
  [[ "$(uname -s 2>/dev/null || true)" == "Darwin" ]]
}

platform_name() {
  local kernel="$(uname -s 2>/dev/null || true)"
  if is_wsl; then
    printf 'WSL 2'
  elif [[ "$kernel" == "Linux" ]]; then
    printf 'Linux'
  elif is_macos; then
    printf 'macOS'
  elif [[ "$kernel" == MINGW* || "$kernel" == MSYS* || "$kernel" == CYGWIN* ]]; then
    printf 'Windows shell bez WSL'
  else
    printf 'Nieznane (%s)' "${kernel:-brak uname}"
  fi
}

platform_architecture() {
  uname -m 2>/dev/null || printf 'nieznana'
}

windows_jdk_home() {
  local candidate home
  is_wsl || return 1
  for candidate in /mnt/c/Program\ Files/Java/*/bin/java.exe \
                  /mnt/c/Program\ Files/JetBrains/*/jbr/bin/java.exe; do
    [[ -f "$candidate" ]] || continue
    home="${candidate%/bin/java.exe}"
    if [[ -f "$home/bin/javac.exe" && -f "$home/bin/keytool.exe" ]]; then
      printf '%s' "$home"
      return 0
    fi
  done
  return 1
}

windows_jdk_available() {
  windows_jdk_home >/dev/null 2>&1
}

print_platform_guidance() {
  local platform="$(platform_name)"
  printf 'System i tryb pracy: %s\n' "$platform"
  printf 'Architektura:         %s\n' "$(platform_architecture)"

  case "$platform" in
    'WSL 2')
      printf 'Rekomendacja: używaj Docker Engine i Compose w WSL 2; Docker Desktop nie jest wymagany.\n'
      printf 'Aplikacje uruchamiaj z IntelliJ na Windows, a skrypty administracyjne z WSL.\n'
      ;;
    Linux)
      printf 'Rekomendacja: używaj Docker Engine/Compose oraz lokalnego JDK.\n'
      printf 'Instalację pakietów dopasuj do dystrybucji; automatyczne apt dotyczy Ubuntu/Debian.\n'
      ;;
    macOS)
      printf 'Rekomendacja: zapewnij Docker Engine lub zgodne środowisko Compose oraz JDK.\n'
      printf 'Polecenia apt i instalacja Vault przez repozytorium HashiCorp nie są automatycznie dobierane.\n'
      ;;
    'Windows shell bez WSL')
      printf 'Brak obsługi natywnego uruchamiania Centrali w tym trybie. Zainstaluj i uruchom WSL 2.\n'
      ;;
    *)
      printf 'Nie rozpoznano platformy. Sprawdź ręcznie Docker/Compose, JDK, Python, Vault i dostęp do projektu.\n'
      ;;
  esac
}

clear_screen() {
  [[ -t 1 ]] && printf '\033[2J\033[H'
}

header() {
  clear_screen
  printf '%s\n' "${C_BLUE}============================================================${C_RESET}"
  printf '%s\n' "${C_BLUE}              NovaBank Control Center${C_RESET}"
  printf '%s\n' "${C_BLUE}============================================================${C_RESET}"
  printf '%s %s\n\n' "$(t common.project)" "$PROJECT_ROOT"
}

section_header() {
  header
  printf '%s%s%s\n\n' "$C_BLUE" "$1" "$C_RESET"
}

pause_menu() {
  local answer=""
  printf '\n%s' "$(t common.pause)"
  IFS= read -r answer || exit 0
  [[ "${answer,,}" == "exit" ]] && exit 0
}

confirm() {
  local prompt="$1"
  local answer=""
  if is_english; then
    case "$prompt" in
      "Uruchomic operacje?") prompt='Run operation?' ;;
      "Uruchomic mimo to?") prompt='Run anyway?' ;;
    esac
  fi
  printf '%s [t/N]: ' "$prompt"
  IFS= read -r answer || return 1
  [[ "${answer,,}" == "t" || "${answer,,}" == "tak" || "${answer,,}" == "y" || "${answer,,}" == "yes" ]]
}

confirm_prod() {
  local answer=""
  printf '%s%s%s\n' "$C_YELLOW" "$(t common.prod_warning)" "$C_RESET"
  printf '%s' "$(t common.prod_confirm)"
  IFS= read -r answer || return 1
  [[ "$answer" == "PROD" ]]
}

log_result() {
  local title="$1"
  local result="$2"
  printf '%s | %s | %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$result" "$title" >> "$ACTIVITY_LOG"
}

print_command() {
  printf '%s' "${C_DIM}$(t common.command)"
  printf ' %q' "$@"
  printf '%s\n' "$C_RESET"
}

run_action() {
  local title="$1"
  local doc="$2"
  local confirmation="$3"
  shift 3
  local status=0

  title="$(localized_action_title "$title")"

  # One stable user-facing guide; legacy menu arguments remain harmless.
  if [[ -n "$doc" ]]; then
    doc="$MAIN_GUIDE_REL"
  fi

  section_header "$title"
  print_command "$@"
  [[ -n "$doc" ]] && printf '%s %s\n' "$(t common.instruction)" "$doc"
  printf '\n'

  case "$confirmation" in
    yes)
      if ! confirm "Uruchomic operacje?"; then
        printf '%s\n' "$(t common.cancelled)"
        pause_menu
        return 0
      fi
      ;;
    prod)
      if ! confirm_prod; then
        printf '%s\n' "$(t common.cancelled)"
        pause_menu
        return 0
      fi
      ;;
  esac

  "$@" || status=$?
  if [[ $status -eq 0 ]]; then
    printf '\n%s%s%s\n' "$C_GREEN" "$(t common.success)" "$C_RESET"
    log_result "$title" "OK"
  else
    printf '\n%s%s%s\n' "$C_RED" "$(printf "$(t common.failure)" "$status")" "$C_RESET"
    log_result "$title" "BLAD:$status"
  fi
  printf '%s %s\n' "$(t common.activity_log)" "$ACTIVITY_LOG"
  pause_menu
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf '%sBrak wymaganej komendy: %s%s\n' "$C_RED" "$1" "$C_RESET" >&2
    return 1
  }
}

random_hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import secrets; print(secrets.token_hex(32))'
  elif command -v xxd >/dev/null 2>&1; then
    xxd -p -l 32 /dev/urandom | tr -d '\n'
  else
    return 1
  fi
}

random_base64() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 32 | tr -d '\n'
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import base64, secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())'
  else
    return 1
  fi
}

generate_regulamin_pdf() {
  local pdf_python="$PDF_VENV/bin/python"

  require_command python3 || return 1
  [[ -f "$PROJECT_ROOT/scripts/requirements-pdf.txt" ]] || {
    printf '%sBrak pliku wymagań PDF: %s%s\n' "$C_RED" "$PROJECT_ROOT/scripts/requirements-pdf.txt" "$C_RESET" >&2
    return 1
  }

  if [[ ! -x "$pdf_python" ]]; then
    printf 'Tworzenie lokalnego środowiska Python dla generatora PDF...\n'
    if ! python3 -m venv "$PDF_VENV"; then
      printf '%sNie udało się utworzyć środowiska Python.%s\n' "$C_RED" "$C_RESET" >&2
      printf 'W WSL doinstaluj pakiet i ponów operację:\n'
      printf '  sudo apt update && sudo apt install -y python3-venv\n'
      return 1
    fi
  fi

  if ! "$pdf_python" -c 'import reportlab' >/dev/null 2>&1; then
    printf 'Instalowanie zależności generatora PDF w %s...\n' "$PDF_VENV"
    if ! "$pdf_python" -m pip install --disable-pip-version-check -q -r "$PROJECT_ROOT/scripts/requirements-pdf.txt"; then
      printf '%sNie udało się zainstalować reportlab.%s\n' "$C_RED" "$C_RESET" >&2
      printf 'Sprawdź połączenie WSL z Internetem lub uruchom ręcznie:\n'
      printf '  %q -m pip install -r %q\n' "$pdf_python" "$PROJECT_ROOT/scripts/requirements-pdf.txt"
      return 1
    fi
  fi

  "$pdf_python" "$PROJECT_ROOT/scripts/generate_regulamin_pdf.py"
}

read_env_value() {
  local file="$1"
  local key="$2"
  [[ -f "$file" ]] || return 0
  awk -v key="$key" '
    index($0, key "=") == 1 {
      sub(/^[^=]*=/, "")
      sub(/\r$/, "")
      print
      exit
    }
  ' "$file"
}

upsert_env_value() {
  local file="$1"
  local key="$2"
  local value="$3"
  local temp

  mkdir -p "$(dirname "$file")"
  touch "$file"
  temp="$(mktemp "${file}.tmp.XXXXXX")"
  awk -v key="$key" -v value="$value" '
    BEGIN { replaced = 0 }
    {
      sub(/\r$/, "")
      if (index($0, key "=") == 1) {
        if (!replaced) print key "=" value
        replaced = 1
      } else {
        print
      }
    }
    END {
      if (!replaced) print key "=" value
    }
  ' "$file" > "$temp"
  mv "$temp" "$file"
  chmod 600 "$file" 2>/dev/null || true
}

prepare_dev_env() {
  local rotate="${1:-false}"
  local api_key totp_key

  api_key="$(read_env_value "$DEV_ENV" BANK_API_KEY)"
  totp_key="$(read_env_value "$DEV_ENV" TOTP_ENCRYPTION_KEY)"

  if [[ "$rotate" == "true" || -z "$api_key" || "$api_key" == "replace-with-a-long-random-secret" ]]; then
    api_key="$(random_hex)" || {
      printf 'Nie mozna wygenerowac BANK_API_KEY. Zainstaluj openssl albo Python 3.\n' >&2
      return 1
    }
    printf 'Wygenerowano nowy lokalny BANK_API_KEY.\n'
  else
    printf 'Zachowano istniejacy lokalny BANK_API_KEY.\n'
  fi

  if [[ -z "$totp_key" || "$totp_key" == "generate-a-32-byte-base64-key" ]]; then
    totp_key="$(random_base64)" || {
      printf 'Nie mozna wygenerowac TOTP_ENCRYPTION_KEY. Zainstaluj openssl albo Python 3.\n' >&2
      return 1
    }
    printf 'Wygenerowano stabilny TOTP_ENCRYPTION_KEY.\n'
  else
    printf 'Zachowano istniejacy TOTP_ENCRYPTION_KEY.\n'
  fi

  upsert_env_value "$DEV_ENV" SPRING_PROFILES_ACTIVE dev
  upsert_env_value "$DEV_ENV" BANK_API_KEY "$api_key"
  upsert_env_value "$DEV_ENV" BANK_API_KEY_ID legacy
  upsert_env_value "$DEV_ENV" BANK_API_KEYS ""
  upsert_env_value "$DEV_ENV" TOTP_ENCRYPTION_KEY "$totp_key"
  printf 'Gotowy plik: %s\n' "$DEV_ENV"
}

prepare_dev() {
  require_command docker || return 1
  [[ -f "$PROJECT_ENV" ]] || {
    printf 'Brak %s. Utworz go na podstawie .env.example.\n' "$PROJECT_ENV" >&2
    return 1
  }
  prepare_dev_env false || return 1
  docker compose --project-directory "$PROJECT_ROOT" -f "$PROJECT_ROOT/docker-compose.yaml" up -d || return 1
  docker compose --project-directory "$PROJECT_ROOT" -f "$PROJECT_ROOT/docker-compose.yaml" ps
  printf '\nSrodowisko DEV jest przygotowane.\n'
  printf 'W IntelliJ uruchom konfiguracje: NovaBank DEV.\n'
}

env_has_real_value() {
  local file="$1"
  local key="$2"
  local value
  value="$(read_env_value "$file" "$key")"
  [[ -n "$value" && "$value" != *'<'* && "$value" != *'replace-'* && "$value" != *'CHANGE_ME'* ]]
}

vault_cert_fingerprint() {
  local file="$1"
  openssl x509 -in "$file" -noout -fingerprint -sha256 2>/dev/null \
    | sed 's/.*=//' \
    | tr -d ':' \
    | tr '[:lower:]' '[:upper:]'
}

vault_live_cert_fingerprint() {
  local address connect
  address="${VAULT_ADDR:-https://127.0.0.1:8200}"
  connect="${address#https://}"
  connect="${connect#http://}"
  connect="${connect%%/*}"
  [[ "$connect" == *:* ]] || connect="$connect:8200"

  echo | openssl s_client -connect "$connect" -servername localhost 2>/dev/null \
    | openssl x509 -noout -fingerprint -sha256 2>/dev/null \
    | sed 's/.*=//' \
    | tr -d ':' \
    | tr '[:lower:]' '[:upper:]'
}

configure_vault_cli_env() {
  if [[ -z "${VAULT_ADDR:-}" ]]; then
    VAULT_ADDR="$(read_env_value "$PROD_BANK_ENV" VAULT_ADDR)"
    [[ -n "$VAULT_ADDR" ]] && export VAULT_ADDR
  fi

  local configured_cert candidate candidate_fingerprint first_readable live_fingerprint
  configured_cert=""
  first_readable=""
  live_fingerprint=""
  if [[ -r /etc/vault.d/vault.hcl ]]; then
    configured_cert="$(awk -F= '/tls_cert_file[[:space:]]*=/{value=$2; gsub(/[ \"\t]/, "", value); print value; exit}' /etc/vault.d/vault.hcl)"
  fi

  # Prefer a readable certificate whose fingerprint matches the live TLS
  # listener. This handles rotated certificates and stale shell variables.
  if command -v openssl >/dev/null 2>&1; then
    live_fingerprint="$(vault_live_cert_fingerprint)"
  fi

  for candidate in \
    "$configured_cert" \
    "${VAULT_CACERT:-}" \
    /opt/vault/tls/tls.crt \
    /opt/vault/tls/vault.crt \
    "$VAULT_CERT"; do
    [[ -n "$candidate" && -r "$candidate" ]] || continue
    [[ -n "$first_readable" ]] || first_readable="$candidate"

    if [[ -z "$live_fingerprint" ]]; then
      VAULT_CACERT="$candidate"
      export VAULT_CACERT
      unset VAULT_CAPATH
      return 0
    fi

    candidate_fingerprint="$(vault_cert_fingerprint "$candidate")"
    if [[ -n "$candidate_fingerprint" && "$candidate_fingerprint" == "$live_fingerprint" ]]; then
      VAULT_CACERT="$candidate"
      export VAULT_CACERT
      unset VAULT_CAPATH
      return 0
    fi
  done

  # Keep the first readable candidate so vault status can print a useful
  # fingerprint mismatch diagnostic instead of hiding the TLS error.
  if [[ -n "$first_readable" ]]; then
    VAULT_CACERT="$first_readable"
    export VAULT_CACERT
    unset VAULT_CAPATH
    return 0
  fi

  if [[ -n "${VAULT_ADDR:-}" ]]; then
    printf 'Nie znaleziono czytelnego certyfikatu CA Vault. Sprawdz /opt/vault/tls/tls.crt albo kopie publiczna w %s.\n' \
      "$VAULT_CERT" >&2
    return 1
  fi
}

vault_command() {
  configure_vault_cli_env || return 1
  "$@"
}

vault_recovery_help() {
  local output="${1:-}"
  local lower="${output,,}"

  printf '\n%sDiagnostyka Vault%s\n' "$C_BLUE" "$C_RESET"
  if [[ "$lower" == *"certificate signed by unknown authority"* || "$lower" == *"unknown authority"* ]]; then
    cat <<EOF
Problem: VAULT_CACERT nie wskazuje certyfikatu aktywnego listenera Vault.
Wykonaj w tej samej sesji WSL:

  export VAULT_ADDR="https://127.0.0.1:8200"
  unset VAULT_CAPATH
  unset VAULT_SKIP_VERIFY

Sprawdz odcisk certyfikatu serwera i plikow:

  echo | openssl s_client -connect 127.0.0.1:8200 -servername localhost 2>/dev/null | openssl x509 -noout -fingerprint -sha256 -subject
  sudo openssl x509 -in /opt/vault/tls/tls.crt -noout -fingerprint -sha256 -subject
  sudo openssl x509 -in /opt/vault/tls/vault.crt -noout -fingerprint -sha256 -subject

Wybierz plik, ktory ma taki sam fingerprint jak certyfikat serwera. Jesli jest to
/opt/vault/tls/vault.crt, wykonaj:

  sudo chmod 0755 /opt/vault/tls
  sudo chmod 0644 /opt/vault/tls/vault.crt
  sudo cp /opt/vault/tls/vault.crt "$VAULT_CERT"
  sudo chown "$USER:$(id -gn)" "$VAULT_CERT"

Nastepnie ustaw:

  export VAULT_CACERT="$VAULT_CERT"
  vault status

VAULT_CACERT musi wskazywac plik o takim samym odcisku jak certyfikat serwera.
Nie wlaczaj VAULT_SKIP_VERIFY.
EOF
  elif [[ "$lower" == *"permission denied"* || "$lower" == *"no readable"* || "$lower" == *"nie znaleziono czytelnego"* ]]; then
    cat <<EOF
Problem: konto WSL nie moze odczytac certyfikatu Vault.
Wykonaj:

  sudo chmod 0755 /opt/vault/tls
  sudo chmod 0644 /opt/vault/tls/tls.crt
  sudo cp /opt/vault/tls/tls.crt "$VAULT_CERT"
  export VAULT_CACERT="$VAULT_CERT"
EOF
  elif [[ "$lower" == *"connection refused"* || "$lower" == *"failed to connect"* ]]; then
    cat <<EOF
Problem: Vault nie nasluchuje pod adresem VAULT_ADDR.
Wykonaj:

  sudo systemctl status vault --no-pager
  sudo systemctl restart vault
  export VAULT_ADDR="https://127.0.0.1:8200"
EOF
  elif [[ "$lower" == *"permission denied"* || "$lower" == *"forbidden"* || "$lower" == *"unauthorized"* ]]; then
    cat <<EOF
    Problem: token operatora nie ma wymaganych uprawnien albo sesja wygasla.
Wykonaj:

  vault login
  vault token lookup
EOF
  else
    cat <<EOF
Nie rozpoznano automatycznie bledu. Sprawdz instrukcje:
  $MAIN_GUIDE_REL

Przydatne polecenia:
  export VAULT_ADDR="https://127.0.0.1:8200"
  vault status
  sudo systemctl status vault --no-pager
EOF
  fi
}

vault_status_once() {
  local output status
  output="$(vault_command vault status 2>&1)"
  status=$?
  printf '%s\n' "$output"
  return "$status"
}

vault_unseal_interactive() {
  local attempt status
  for attempt in 1 2 3 4 5; do
    printf '\nKlucz unseal %s/3 (wprowadz udzial klucza, nie root token):\n' "$attempt"
    vault_command vault operator unseal || {
      printf '%sNie udalo sie wykonac operacji unseal.%s\n' "$C_RED" "$C_RESET"
      return 1
    }

    vault_status_once >/dev/null 2>&1
    status=$?
    if [[ $status -eq 0 ]]; then
      printf '%sVault jest odpieczetowany. Kontynuuje operacje.%s\n' "$C_GREEN" "$C_RESET"
      return 0
    fi
    if [[ $status -ne 2 ]]; then
      printf '%sNie mozna ponownie sprawdzic stanu Vault.%s\n' "$C_RED" "$C_RESET"
      return 1
    fi
  done

  printf '%sNie uzyskano stanu Sealed: false po probach unseal.%s\n' "$C_RED" "$C_RESET"
  printf 'Sprawdz, czy udzialy pochodza z tego samego pliku vault-init.json.gpg.\n'
  return 1
}

vault_prepare_operation() {
  local output status answer

  while true; do
    output="$(vault_command vault status 2>&1)"
    status=$?
    printf '%s\n' "$output"

    if [[ $status -eq 0 ]]; then
      return 0
    fi

    if [[ $status -eq 2 || "${output,,}" == *"vault is sealed"* ]]; then
      printf '\n%sVault jest zapieczetowany.%s\n' "$C_YELLOW" "$C_RESET"
      printf 'Odpieczetowac teraz trzema udzialami klucza? [t/N]: '
      IFS= read -r answer || return 1
      if [[ "${answer,,}" == "t" || "${answer,,}" == "tak" || "${answer,,}" == "y" ]]; then
        vault_unseal_interactive && return 0
        return 1
      fi
      printf 'Anulowano operacje. Wybierz ponownie opcje po odpieczetowaniu Vault.\n'
      return 1
    fi

    vault_recovery_help "$output"
    printf '\nN - ponow status, I - instrukcja, Q - powrot do menu: '
    IFS= read -r answer || return 1
    case "${answer,,}" in
      n|ponow) continue ;;
      i|instrukcja) printf 'Dokument: %s\n' "$MAIN_GUIDE_REL"; continue ;;
      *) return 1 ;;
    esac
  done
}

vault_status_action() {
  printf '\nAktualny status Vault:\n'
  local status=0
  vault_status_once || status=$?
  case "$status" in
    0)
      printf 'Vault jest odpieczetowany.\n'
      return 0
      ;;
    2)
      printf 'Vault jest zapieczetowany. Wybierz osobna opcje odpieczetowania.\n'
      return 0
      ;;
    *)
      vault_recovery_help "$(vault_status_once 2>&1 || true)"
      return "$status"
      ;;
  esac
}

vault_unseal_action() {
  configure_vault_cli_env || return 1
  local output status
  output="$(vault_command vault status 2>&1)"
  status=$?
  printf '%s\n' "$output"
  if [[ $status -eq 0 ]]; then
    printf 'Vault jest juz odpieczetowany.\n'
    return 0
  fi
  if [[ $status -ne 2 ]]; then
    vault_recovery_help "$output"
    return "$status"
  fi
  vault_unseal_interactive
}

vault_operation() {
  vault_prepare_operation || return 1
  "$@"
}

run_maven() {
  local arg escaped command_text win_mvnw win_project_root previous_arg
  local windows_java_home windows_java_home_win

  if command -v java >/dev/null 2>&1 && command -v javac >/dev/null 2>&1; then
    if [[ -n "${JAVA_HOME:-}" && ! -x "$JAVA_HOME/bin/java" ]]; then
      unset JAVA_HOME
    fi
    "$PROJECT_ROOT/mvnw" "$@"
    return
  fi

  if is_wsl && command -v powershell.exe >/dev/null 2>&1 && [[ -f "$PROJECT_ROOT/mvnw.cmd" ]]; then
    win_mvnw="$(wslpath -w "$PROJECT_ROOT/mvnw.cmd")" || return 1
    win_project_root="$(wslpath -w "$PROJECT_ROOT")" || return 1
    command_text="\$ErrorActionPreference='Stop'; "
    if windows_java_home="$(windows_jdk_home 2>/dev/null)"; then
      windows_java_home_win="$(wslpath -w "$windows_java_home")" || return 1
      escaped="${windows_java_home_win//\'/\'\'}"
      command_text+="\$env:JAVA_HOME='$escaped'; "
    fi
    command_text+="Set-Location -LiteralPath '"
    escaped="${win_project_root//\'/\'\'}"
    command_text+="$escaped'; & '"
    escaped="${win_mvnw//\'/\'\'}"
    command_text+="$escaped'"
    previous_arg=''
    for arg in "$@"; do
      # mvnw.cmd runs in Windows and cannot resolve WSL paths passed to -f.
      if [[ "$previous_arg" == '-f' && "$arg" == /* ]]; then
        arg="$(wslpath -w "$arg")" || return 1
      fi
      escaped="${arg//\'/\'\'}"
      command_text+=" '$escaped'"
      previous_arg="$arg"
    done
    printf 'WSL nie ma pelnego JDK. Uruchamiam Maven przez Windows JDK: %s\n' "$win_mvnw"
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$command_text"
    return
  fi

  printf 'Brak dzialajacego JDK (java i javac). Zainstaluj JDK w WSL albo ustaw JAVA_HOME na JDK.\n' >&2
  return 1
}

run_all_unit_tests() {
  run_maven -Punit-tests test || return 1
  run_maven -f "$PROJECT_ROOT/shop-app/pom.xml" -Punit-tests test
}

run_e2e_smoke() {
  local bank_api_key bank_api_key_id

  bank_api_key="${BANK_API_KEY:-$(read_env_value "$DEV_ENV" BANK_API_KEY)}"
  bank_api_key_id="${BANK_API_KEY_ID:-$(read_env_value "$DEV_ENV" BANK_API_KEY_ID)}"

  if [[ -z "$bank_api_key" ]]; then
    printf '%sBrak BANK_API_KEY. Uruchom Przygotowanie DEV albo ustaw klucz używany przez bank i sklep.%s\n' \
      "$C_RED" "$C_RESET" >&2
    return 1
  fi

  printf 'Uruchamiam E2E z kluczem DEV z konfiguracji Centrali (id: %s).\n' "${bank_api_key_id:-legacy}"
  BANK_URL="${BANK_URL:-http://127.0.0.1:8080}" \
  SHOP_URL="${SHOP_URL:-http://127.0.0.1:8081}" \
  BANK_HEALTH_URL="${BANK_HEALTH_URL:-http://127.0.0.1:8082/actuator/health}" \
  SHOP_HEALTH_URL="${SHOP_HEALTH_URL:-http://127.0.0.1:8083/actuator/health}" \
  BANK_API_KEY="$bank_api_key" \
  BANK_API_KEY_ID="${bank_api_key_id:-legacy}" \
  bash "$PROJECT_ROOT/scripts/e2e-smoke.sh"
}

prepare_prod() {
  local failed=false
  require_command docker || failed=true
  require_command vault || failed=true

  for file in "$PROD_BANK_ENV" "$PROD_SHOP_ENV"; do
    if [[ ! -f "$file" ]]; then
      printf '%sBrak pliku bootstrap: %s%s\n' "$C_RED" "$file" "$C_RESET"
      failed=true
      continue
    fi
    for key in VAULT_ADDR VAULT_ROLE VAULT_ROLE_ID VAULT_SECRET_ID VAULT_SSL_TRUST_STORE VAULT_SSL_TRUST_STORE_PASSWORD; do
      if ! env_has_real_value "$file" "$key"; then
        printf '%sBrak poprawnej wartosci %s w %s%s\n' "$C_RED" "$key" "$file" "$C_RESET"
        failed=true
      fi
    done
  done

  for file in "$BANK_KEYSTORE" "$SHOP_KEYSTORE"; do
    [[ -f "$file" ]] || {
      printf '%sBrak keystore: %s%s\n' "$C_RED" "$file" "$C_RESET"
      failed=true
    }
  done

  if command -v systemctl >/dev/null 2>&1; then
    if systemctl is-active --quiet vault 2>/dev/null; then
      printf '%sVault service: ACTIVE%s\n' "$C_GREEN" "$C_RESET"
    else
      printf '%sVault service: NIEAKTYWNY%s\n' "$C_RED" "$C_RESET"
      failed=true
    fi
  fi

  if command -v vault >/dev/null 2>&1; then
    configure_vault_cli_env
    local vault_status=0
    vault status >/dev/null 2>&1 || vault_status=$?
    case "$vault_status" in
      0) printf '%sVault: odpieczetowany%s\n' "$C_GREEN" "$C_RESET" ;;
      2) printf '%sVault: zapieczetowany. TLS dziala, ale trzeba podac trzy rozne klucze unseal.%s\n' "$C_RED" "$C_RESET"; failed=true ;;
      *) printf '%sNie mozna pobrac statusu Vault. Sprawdz VAULT_ADDR i VAULT_CACERT.%s\n' "$C_RED" "$C_RESET"; failed=true ;;
    esac
  fi

  [[ "$failed" == "false" ]] || return 1
  docker compose --project-directory "$PROJECT_ROOT" -f "$PROJECT_ROOT/docker-compose.yaml" up -d || return 1
  printf '\nSrodowisko PROD jest przygotowane.\n'
  printf 'W IntelliJ uruchom konfiguracje: NovaBank PROD.\n'
}

run_powershell_script() {
  local script="$1"
  shift
  if is_wsl && command -v powershell.exe >/dev/null 2>&1; then
    local windows_script
    windows_script="$(wslpath -w "$script")" || return 1
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$windows_script" "$@"
  elif command -v pwsh >/dev/null 2>&1; then
    pwsh -NoProfile -File "$script" "$@"
  else
    printf 'Ta operacja wymaga PowerShell 7 albo WSL z dostepem do powershell.exe.\n' >&2
    return 1
  fi
}

show_infrastructure_status() {
  require_command docker || return 1
  printf '%sDocker Compose:%s\n' "$C_BLUE" "$C_RESET"
  docker compose --project-directory "$PROJECT_ROOT" -f "$PROJECT_ROOT/docker-compose.yaml" ps
  printf '\n%sPorty aplikacji:%s\n' "$C_BLUE" "$C_RESET"
  for item in \
    'Bank API|http://127.0.0.1:8080/login' \
    'Sklep API|http://127.0.0.1:8081/api/products' \
    'Metryki banku|http://127.0.0.1:8082/actuator/health/readiness' \
    'Metryki sklepu|http://127.0.0.1:8083/actuator/health/readiness'; do
    local name="${item%%|*}"
    local url="${item#*|}"
    if command -v curl >/dev/null 2>&1 && curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
      printf '%-18s %sUP%s\n' "$name" "$C_GREEN" "$C_RESET"
    else
      printf '%-18s %sDOWN albo niedostepny z tego systemu%s\n' "$name" "$C_YELLOW" "$C_RESET"
    fi
  done
}

compose_project() {
  docker compose --project-directory "$PROJECT_ROOT" --env-file "$PROJECT_ENV" -f "$PROJECT_ROOT/docker-compose.yaml" "$@"
}

docker_infrastructure_status() {
  check_docker_engine || return 1
  compose_project ps
  printf '\nStan healthcheckow kontenerow:\n'
  local container health
  for container in bank-postgres shop-postgres bank-redis; do
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || printf 'brak')"
    printf '  %-16s %s\n' "$container" "$health"
  done
  printf '\nSzybkie testy usług:\n'
  local bank_user bank_db shop_user shop_db
  bank_user="$(read_env_value "$PROJECT_ENV" POSTGRES_USER)"
  bank_db="$(read_env_value "$PROJECT_ENV" POSTGRES_DB)"
  shop_user="$(read_env_value "$PROJECT_ENV" SHOP_POSTGRES_USER)"
  shop_db="$(read_env_value "$PROJECT_ENV" SHOP_POSTGRES_DB)"
  bank_user="${bank_user:-bankuser}"
  bank_db="${bank_db:-bankdb}"
  shop_user="${shop_user:-shopuser}"
  shop_db="${shop_db:-shopdb}"
  docker exec bank-redis redis-cli ping 2>/dev/null | sed 's/^/  Redis: /' || printf '  Redis: BLAD\n'
  docker exec bank-postgres pg_isready -U "$bank_user" -d "$bank_db" 2>/dev/null | sed 's/^/  Bank PostgreSQL: /' || printf '  Bank PostgreSQL: BLAD\n'
  docker exec shop-postgres pg_isready -U "$shop_user" -d "$shop_db" 2>/dev/null | sed 's/^/  Shop PostgreSQL: /' || printf '  Shop PostgreSQL: BLAD\n'
}

start_project_databases() {
  [[ -f "$PROJECT_ENV" ]] || {
    printf 'Brak %s. Uzupelnij go przed uruchomieniem kontenerow.\n' "$PROJECT_ENV"
    return 1
  }
  check_docker_engine || return 1
  printf 'Compose uruchomi obrazy:\n'
  printf '  postgres:18       -> bank-postgres i shop-postgres\n'
  printf '  redis:7.4-alpine  -> bank-redis\n\n'
  compose_project up -d postgres shop-postgres redis || return 1
  compose_project ps
}

restart_project_databases() {
  check_docker_engine || return 1
  compose_project up -d postgres shop-postgres redis || return 1
  compose_project restart postgres shop-postgres redis || return 1
  compose_project ps
}

stop_project_databases() {
  check_docker_engine || return 1
  printf 'Zatrzymuje tylko kontenery PostgreSQL i Redis. Wolumeny pozostaja bez zmian.\n'
  compose_project stop postgres shop-postgres redis
}

project_database_logs() {
  check_docker_engine || return 1
  compose_project logs --tail=120 postgres shop-postgres redis
}

validate_project_compose() {
  [[ -f "$PROJECT_ENV" ]] || {
    printf 'Brak %s. Nie mozna zweryfikowac interpolacji Compose.\n' "$PROJECT_ENV"
    return 1
  }
  check_docker_engine || return 1
  compose_project config --quiet
  printf 'Konfiguracja docker-compose.yaml jest poprawna.\n'
}

flyway_database_status() {
  local label="$1" container user database
  case "$label" in
    bank)
      container=bank-postgres
      user="$(read_env_value "$PROJECT_ENV" POSTGRES_USER)"
      database="$(read_env_value "$PROJECT_ENV" POSTGRES_DB)"
      ;;
    shop)
      container=shop-postgres
      user="$(read_env_value "$PROJECT_ENV" SHOP_POSTGRES_USER)"
      database="$(read_env_value "$PROJECT_ENV" SHOP_POSTGRES_DB)"
      ;;
    *) printf 'Nieznana baza: %s\n' "$label" >&2; return 1 ;;
  esac
  user="${user:-${label}user}"
  database="${database:-${label}db}"
  printf '\n%s (%s):\n' "$label" "$database"
  docker exec "$container" psql -v ON_ERROR_STOP=1 -U "$user" -d "$database" -P pager=off -c \
    "SELECT current_database() AS baza, COUNT(*) AS migracje, COALESCE(MAX(installed_rank), 0) AS ostatni_rank, COALESCE(MAX(version), 'brak') AS ostatnia_wersja, SUM(CASE WHEN success THEN 0 ELSE 1 END) AS bledne FROM flyway_schema_history;"
}

flyway_diagnostics() {
  check_docker_engine || return 1
  [[ -f "$PROJECT_ENV" ]] || {
    printf 'Brak %s.\n' "$PROJECT_ENV"
    return 1
  }
  flyway_database_status bank || return 1
  flyway_database_status shop || return 1
  printf '\nFlyway wykonuje migracje przy starcie odpowiedniej aplikacji.\n'
  printf 'Control Center nie uruchamia polecenia repair i nie usuwa historii migracji.\n'
}

redis_session_count() {
  docker exec bank-redis redis-cli --scan --pattern 'spring:session:sessions:*' 2>/dev/null | wc -l | tr -d ' '
}

redis_session_status() {
  check_docker_engine || return 1
  local count info
  info="$(docker exec bank-redis redis-cli ping 2>/dev/null)" || {
    printf 'Nie mozna polaczyc sie z bank-redis.\n'
    return 1
  }
  printf 'Redis: %s\n' "$info"
  count="$(redis_session_count)"
  printf 'Sesje BANK_SESSION: %s\n' "$count"
  printf 'Zakres kluczy sesji: spring:session:*\n'
  printf 'Sklep korzysta z sesji JDBC SHOP_SESSION, wiec ta operacja nie wylogowuje sklepu.\n'
}

invalidate_bank_sessions() {
  check_docker_engine || return 1
  local count answer deleted
  count="$(redis_session_count)"
  printf 'Znaleziono %s rekordow sesji banku.\n' "$count"
  printf 'Usuniete zostana tylko klucze spring:session:*; dane rate limiting pozostana.\n'
  printf 'To wyloguje wszystkich uzytkownikow banku. Kontynuowac? [t/N]: '
  IFS= read -r answer || return 1
  [[ "${answer,,}" == "t" || "${answer,,}" == "tak" || "${answer,,}" == "y" ]] || {
    printf 'Anulowano.\n'
    return 0
  }
  deleted="$(docker exec bank-redis redis-cli --raw EVAL \
    "local c='0'; local d=0; repeat local r=redis.call('SCAN',c,'MATCH','spring:session:*','COUNT',500); c=r[1]; for _,k in ipairs(r[2]) do redis.call('DEL',k); d=d+1 end until c=='0'; return d" 0)" || return 1
  printf 'Usunieto kluczy sesji: %s\n' "$deleted"
}

local_artifact_cleanup() {
  local artifact_days="${LOCAL_ARTIFACT_RETENTION_DAYS:-30}"
  local temp_days="${LOCAL_TEMP_RETENTION_DAYS:-7}"
  local mode="${LOCAL_ARTIFACT_CLEANUP_MODE:-archive}"
  local run_id archive_dir archive_candidates temp_candidates temp_unique
  local candidate relative destination archived=0 removed=0 skipped=0

  [[ "$artifact_days" =~ ^[0-9]+$ ]] || artifact_days=30
  [[ "$temp_days" =~ ^[0-9]+$ ]] || temp_days=7
  case "$mode" in
    archive|delete) ;;
    *)
      printf 'Nieprawidlowy LOCAL_ARTIFACT_CLEANUP_MODE=%s. Uzyj archive albo delete.\n' "$mode"
      return 1
      ;;
  esac

  run_id="$(date -u +%Y%m%dT%H%M%SZ)"
  archive_dir="$ACTIVITY_DIR/artifacts-archive/$run_id"
  archive_candidates="$(mktemp "$ACTIVITY_DIR/.artifact-archive.XXXXXX")" || return 1
  temp_candidates="$(mktemp "$ACTIVITY_DIR/.artifact-temp.XXXXXX")" || {
    rm -f "$archive_candidates"
    return 1
  }
  temp_unique="$(mktemp "$ACTIVITY_DIR/.artifact-temp-unique.XXXXXX")" || {
    rm -f "$archive_candidates" "$temp_candidates"
    return 1
  }

  printf 'Porzadkowanie artefaktow lokalnych\n'
  printf 'Retencja logow i raportow: %s dni\n' "$artifact_days"
  printf 'Retencja plikow tymczasowych: %s dni\n' "$temp_days"
  printf 'Tryb starych logow i raportow: %s\n' "$mode"

  find "$PROJECT_ROOT" -type f -name 'hs_err_pid*.log' \
    -mtime "+$artifact_days" -print >> "$archive_candidates"
  find "$PROJECT_ROOT" -type f \( -iname '*stripe*.log' -o -iname '*stripe*.txt' \) \
    -not -path '*/target/*' -mtime "+$artifact_days" -print >> "$archive_candidates"
  find "$PROJECT_ROOT" -type f \( \
      -path '*/target/surefire-reports/*' -o \
      -path '*/target/failsafe-reports/*' -o \
      -path '*/target/test-results/*' \
    \) -mtime "+$artifact_days" -print >> "$archive_candidates"
  sort -u "$archive_candidates" -o "$archive_candidates"

  if [[ -d "$PROJECT_ROOT/tmp" ]]; then
    find "$PROJECT_ROOT/tmp" \
      -path "$ACTIVITY_DIR" -prune -o \
      -type f -mtime "+$temp_days" -print >> "$temp_candidates"
  fi
  grep -Fvx -f "$archive_candidates" "$temp_candidates" > "$temp_unique" || true

  printf '\nStare logi i raporty do obslugi: %s\n' "$(wc -l < "$archive_candidates" | tr -d ' ')"
  printf 'Wygasle pliki tymczasowe do usuniecia: %s\n' "$(wc -l < "$temp_unique" | tr -d ' ')"

  if [[ -s "$archive_candidates" ]]; then
    [[ "$mode" == archive ]] && mkdir -p "$archive_dir"
    while IFS= read -r candidate; do
      [[ -f "$candidate" ]] || continue
      relative="${candidate#"$PROJECT_ROOT/"}"
      if [[ "$mode" == archive ]]; then
        destination="$archive_dir/$relative"
        mkdir -p "$(dirname "$destination")"
        if mv "$candidate" "$destination"; then
          printf 'Zarchiwizowano: %s -> %s\n' "$relative" "${destination#"$PROJECT_ROOT/"}"
          archived=$((archived + 1))
        else
          printf 'BLAD archiwizacji: %s\n' "$relative"
          skipped=$((skipped + 1))
        fi
      elif rm -f "$candidate"; then
        printf 'Usunieto: %s\n' "$relative"
        removed=$((removed + 1))
      else
        printf 'BLAD usuwania: %s\n' "$relative"
        skipped=$((skipped + 1))
      fi
    done < "$archive_candidates"
  fi

  if [[ -s "$temp_unique" ]]; then
    while IFS= read -r candidate; do
      [[ -f "$candidate" ]] || continue
      relative="${candidate#"$PROJECT_ROOT/"}"
      if rm -f "$candidate"; then
        printf 'Usunieto wygasly plik tymczasowy: %s\n' "$relative"
        removed=$((removed + 1))
      else
        printf 'BLAD usuwania pliku tymczasowego: %s\n' "$relative"
        skipped=$((skipped + 1))
      fi
    done < "$temp_unique"
  fi

  rm -f "$archive_candidates" "$temp_candidates" "$temp_unique"
  printf '\nPodsumowanie porzadkowania:\n'
  printf '  Zarchiwizowano: %s\n' "$archived"
  printf '  Usunieto:       %s\n' "$removed"
  printf '  Pominieto/bledy: %s\n' "$skipped"
  if [[ "$archived" -gt 0 ]]; then
    printf '  Archiwum:       %s\n' "$archive_dir"
  else
    printf '  Archiwum:       brak nowych plikow\n'
  fi
  [[ "$skipped" -eq 0 ]]
}

first_run_value_ready() {
  local value="${1:-}"
  [[ -n "$value" \
    && "$value" != *'<'* \
    && "$value" != *'>'* \
    && "$value" != *'CHANGE_ME'* \
    && "$value" != *'change-me'* \
    && "$value" != *'replace-with-'* \
    && "$value" != *'RZECZYWISTY_'* \
    && "$value" != *'HASLO_'* ]]
}

prompt_required_value() {
  local label="$1"
  local default_value="${2:-}"
  local entered=""
  REPLY=""
  printf '%s' "$label"
  [[ -n "$default_value" ]] && printf ' [%s]' "$default_value"
  printf ': '
  IFS= read -r entered || return 1
  REPLY="${entered:-$default_value}"
  [[ -n "$REPLY" ]]
}

prompt_required_secret() {
  local label="$1"
  local current="${2:-}"
  local entered=""
  if first_run_value_ready "$current"; then
    REPLY="$current"
    return 0
  fi
  REPLY=""
  printf '%s' "$label"
  IFS= read -r -s entered || return 1
  printf '\n'
  REPLY="$entered"
  [[ -n "$REPLY" ]]
}

create_root_env() {
  local file="$PROJECT_ENV"
  local value

  if [[ ! -f "$file" ]]; then
    [[ -f "$PROJECT_ROOT/.env.example" ]] || {
      printf 'Brak szablonu: %s/.env.example\n' "$PROJECT_ROOT" >&2
      return 1
    }
    cp "$PROJECT_ROOT/.env.example" "$file" || return 1
    printf 'Utworzono %s na podstawie .env.example.\n' "$file"
  else
    printf 'Znaleziono istniejący %s. Zachowuję pozostałe wartości.\n' "$file"
  fi

  for key in POSTGRES_PASSWORD SHOP_POSTGRES_PASSWORD; do
    value="$(read_env_value "$file" "$key")"
    if ! first_run_value_ready "$value"; then
      value="$(random_hex)" || {
        printf 'Nie można wygenerować hasła dla %s. Zainstaluj openssl albo Python 3.\n' "$key" >&2
        return 1
      }
      printf 'Control Center wygenerował lokalne hasło dla %s.\n' "$key"
      upsert_env_value "$file" "$key" "$value"
    else
      printf 'Zachowano %s.\n' "$key"
    fi
  done

  chmod 600 "$file" 2>/dev/null || true
  printf 'Gotowy plik: %s\n' "$file"
  printf 'Następnie uruchom opcję "Uruchom PostgreSQL i Redis".\n'
}

create_dev_env_file() {
  prepare_dev_env false || return 1
  printf 'Gotowy plik: %s\n' "$DEV_ENV"
  printf 'Control Center automatycznie utworzył lub zachował BANK_API_KEY i TOTP_ENCRYPTION_KEY dla DEV.\n'
  printf 'Następnie uruchom konfigurację NovaBank DEV w IntelliJ.\n'
}

prod_truststore_default() {
  local path="$TRUSTSTORE"
  local windows_path
  if is_wsl && command -v wslpath >/dev/null 2>&1; then
    windows_path="$(wslpath -w "$path")" || return 1
    windows_path="${windows_path//\\//}"
    printf 'file:%s' "$windows_path"
  else
    printf 'file:%s' "$path"
  fi
}

create_prod_bootstrap_files() {
  local truststore_default truststore_password
  local file role existing role_id secret_id

  truststore_default="$(prod_truststore_default)" || return 1
  printf 'Pliki PROD zawierają wyłącznie dane bootstrap AppRole i truststore.\n'
  printf 'Sekrety biznesowe pozostają w Vault.\n'
  printf 'Domyślna ścieżka truststore: %s\n\n' "$truststore_default"

  truststore_password="$(read_env_value "$PROD_BANK_ENV" VAULT_SSL_TRUST_STORE_PASSWORD)"
  if ! first_run_value_ready "$truststore_password"; then
    prompt_required_secret 'Podaj hasło utworzone dla vault-truststore.p12: ' "$truststore_password" || {
      printf 'Nie podano hasła truststore. Pliki zostaną utworzone jako niekompletne.\n' >&2
      truststore_password='CHANGE_ME_TRUSTSTORE_PASSWORD'
    }
    [[ "${REPLY:-}" != '' ]] && truststore_password="$REPLY"
  fi

  for role in bank shop; do
    if [[ "$role" == bank ]]; then
      file="$PROD_BANK_ENV"
      role='bank-prod'
    else
      file="$PROD_SHOP_ENV"
      role='shop-prod'
    fi
    mkdir -p "$(dirname "$file")"
    touch "$file"
    upsert_env_value "$file" VAULT_ADDR 'https://127.0.0.1:8200'
    upsert_env_value "$file" VAULT_ROLE "$role"
    upsert_env_value "$file" VAULT_SSL_TRUST_STORE "$truststore_default"
    upsert_env_value "$file" VAULT_SSL_TRUST_STORE_PASSWORD "$truststore_password"
    upsert_env_value "$file" VAULT_SSL_TRUST_STORE_TYPE PKCS12
    upsert_env_value "$file" VAULT_APPROLE_PATH approle

    existing="$(read_env_value "$file" VAULT_ROLE_ID)"
    prompt_required_value "Podaj VAULT_ROLE_ID dla $role" "$existing" || true
    role_id="${REPLY:-RZECZYWISTY_ROLE_ID}"
    upsert_env_value "$file" VAULT_ROLE_ID "$role_id"

    existing="$(read_env_value "$file" VAULT_SECRET_ID)"
    prompt_required_secret "Podaj VAULT_SECRET_ID dla $role: " "$existing" || true
    secret_id="${REPLY:-RZECZYWISTY_SECRET_ID}"
    upsert_env_value "$file" VAULT_SECRET_ID "$secret_id"
    chmod 600 "$file" 2>/dev/null || true
    printf 'Gotowy plik: %s\n' "$file"
  done

  printf '\nJeśli pozostawiono placeholdery, wykonaj najpierw konfigurację AppRole i truststore.\n'
  show_prod_secret_locations
  printf 'Instrukcja: %s\n' "$MAIN_GUIDE_REL"
}

show_prod_secret_locations() {
  cat <<EOF

Mapa danych PROD - zapisz je tylko w tych miejscach:

  .novabank/env/prod.bank.env
    VAULT_ROLE_ID, VAULT_SECRET_ID
    VAULT_SSL_TRUST_STORE_PASSWORD

  .novabank/env/prod.shop.env
    VAULT_ROLE_ID, VAULT_SECRET_ID
    VAULT_SSL_TRUST_STORE_PASSWORD

  .novabank/tls/vault/vault-truststore.p12
    Plik truststore utworzony przez keytool; hasło powyżej jest potrzebne
    w obu plikach .novabank/env/prod.*.env

  Vault KV: secret/Bank-demo/prod
    CERTIFICATE_MONITOR_ENABLED, CERTIFICATE_MONITOR_SCHEDULE oraz VAULT_TLS_CERTIFICATE_PATH
    Hasło bazy banku, BANK_API_KEYS, TOTP_ENCRYPTION_KEY,
    SERVER_SSL_KEY_STORE_PASSWORD oraz pozostałe sekrety banku

  Vault KV: secret/shop-app/prod
    CERTIFICATE_MONITOR_ENABLED, CERTIFICATE_MONITOR_SCHEDULE oraz VAULT_TLS_CERTIFICATE_PATH
    Hasło bazy sklepu, BANK_API_KEY, BANK_API_KEY_ID,
    SERVER_SSL_KEY_STORE_PASSWORD oraz pozostałe sekrety sklepu

  Vault recovery: zewnętrzny zaszyfrowany plik vault-init.json.gpg
    Pięć kluczy unseal i root token; nie przechowuj go w repozytorium

  Pliki .novabank/env/*, keystory i recovery muszą pozostać poza Git.
  Control Center nie wyświetla ani nie zapisuje wartości sekretów w logu.
EOF
}

first_run_status() {
  local file value failures=0
  printf 'NovaBank - kontrola pierwszego uruchomienia\n\n'

  file="$PROJECT_ENV"
  if [[ -f "$file" ]] \
    && first_run_value_ready "$(read_env_value "$file" POSTGRES_PASSWORD)" \
    && first_run_value_ready "$(read_env_value "$file" SHOP_POSTGRES_PASSWORD)"; then
    printf '%-28s GOTOWY\n' "${file#"$PROJECT_ROOT/"}"
  else
    printf '%-28s BRAK lub nieuzupełniony\n' "${file#"$PROJECT_ROOT/"}"
    failures=$((failures + 1))
  fi

  if [[ -s "$DEV_ENV" ]] \
    && env_has_real_value "$DEV_ENV" BANK_API_KEY \
    && env_has_real_value "$DEV_ENV" TOTP_ENCRYPTION_KEY; then
    printf '%-28s GOTOWY\n' "${DEV_ENV#"$PROJECT_ROOT/"}"
  else
    printf '%-28s DO UTWORZENIA/UZUPELNIENIA\n' "${DEV_ENV#"$PROJECT_ROOT/"}"
  fi

  for file in "$PROD_BANK_ENV" "$PROD_SHOP_ENV"; do
    if [[ -s "$file" ]] \
      && env_has_real_value "$file" VAULT_ADDR \
      && env_has_real_value "$file" VAULT_ROLE \
      && env_has_real_value "$file" VAULT_ROLE_ID \
      && env_has_real_value "$file" VAULT_SECRET_ID \
      && env_has_real_value "$file" VAULT_SSL_TRUST_STORE \
      && env_has_real_value "$file" VAULT_SSL_TRUST_STORE_PASSWORD; then
      printf '%-28s GOTOWY\n' "${file#"$PROJECT_ROOT/"}"
    else
      printf '%-28s WYMAGA RECZNYCH DANYCH PROD\n' "${file#"$PROJECT_ROOT/"}"
    fi
  done

  if [[ -s "$MONITORING_ENV" \
    && -s "$PROMETHEUS_CONFIG" ]]; then
    printf 'monitoring dev               GOTOWY\n'
  else
    printf 'monitoring dev               OPCJONALNY/BRAK\n'
  fi

  printf '\nKolejność przygotowania:\n'
  printf '1. Zainstaluj wymagane komponenty.\n'
  printf '2. Utwórz lub uzupełnij .novabank/env/project.env.\n'
  printf '3. Uruchom PostgreSQL i Redis.\n'
  printf '4. Utwórz pliki .novabank/env/dev.env albo pliki PROD.\n'
  printf '5. Dla PROD skonfiguruj i odpieczętuj Vault oraz utwórz TLS.\n'
  printf '6. Uruchom gotową konfigurację w IntelliJ.\n'
  return "$failures"
}

first_run_menu() {
  while true; do
    section_header "$(t first.title)"
    printf '%s\n' \
      "1. $(t first.1)" \
      "2. $(t first.2)" \
      "3. $(t first.3)" \
      "4. $(t first.4)" \
      "5. $(t first.5)" \
      "6. $(t first.6)" \
      "7. $(t first.7)" \
      "8. $(t first.8)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Kontrola pierwszego uruchomienia" "docs/first-run.md" none first_run_status ;;
      2) run_action "Utworzenie .novabank/env/project.env" "docs/first-run.md" yes create_root_env ;;
      3) run_action "Utworzenie .novabank/env/dev.env" "docs/intellij-dev.md" yes create_dev_env_file ;;
      4) run_action "Utworzenie plików bootstrap PROD" "docs/VAULT-PROD-INSTRUKCJA.md" yes create_prod_bootstrap_files ;;
      5) run_action "Mapa sekretów PROD" "docs/first-run.md" none show_prod_secret_locations ;;
      6) run_action "Przygotowanie monitoringu DEV" "docs/monitoring-preparation.md" yes bash "$PROJECT_ROOT/scripts/monitoring/monitoring.sh" setup --profile dev ;;
      7) run_action "Kontrola wymaganych komponentów" "docs/General_Instruction.md" none check_installation_status ;;
      8) run_action "Instrukcja pierwszego uruchomienia" "$MAIN_GUIDE_REL" none show_document "$PROJECT_ROOT/$MAIN_GUIDE_REL" ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

show_intellij_configurations() {
  cat <<'EOF'
Gotowe konfiguracje wspoldzielone:

  NovaBank DEV
    - Bank DEV
    - Shop DEV
    - profil dev i .novabank/env/dev.env

  NovaBank PROD
    - Bank PROD z .novabank/env/prod.bank.env
    - Shop PROD z .novabank/env/prod.shop.env

  NovaBank Control Center
    - uruchamia to menu przez WSL

Po zmianie pliku env zatrzymaj i uruchom ponownie odpowiednia konfiguracje.
Sekrety nie sa zapisane w plikach .run.
EOF
}

show_document() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    printf 'Nie znaleziono instrukcji: %s\n' "$file" >&2
    return 1
  fi
  printf 'Plik: %s\n\n' "$file"
  if command -v less >/dev/null 2>&1 && [[ -t 0 && -t 1 ]]; then
    less -R "$file"
  else
    cat "$file"
  fi
}

launch_interactive_guide() {
  bash "$PROJECT_ROOT/scripts/control-center/interactive-guide.sh"
}

check_installation_status() {
  local failures=0 command_name
  local -a missing_actions=()
  printf 'NovaBank - kontrola wymaganych komponentow\n\n'
  print_platform_guidance
  printf '\n'

  for command_name in bash curl jq openssl gpg docker vault stripe java javac keytool python3; do
    if command -v "$command_name" >/dev/null 2>&1; then
      printf '%-12s OK (%s)\n' "$command_name" "$(command -v "$command_name")"
    elif [[ "$command_name" =~ ^(java|javac|keytool)$ ]] && windows_jdk_available; then
      printf '%-12s OK (JDK Windows przez fallback)\n' "$command_name"
    elif [[ "$command_name" == "stripe" ]]; then
      printf '%-12s OPCJONALNE - brak\n' "$command_name"
    else
      printf '%-12s BRAK\n' "$command_name"
      failures=$((failures + 1))
      case "$command_name" in
        bash|curl|jq|openssl|gpg)
          missing_actions+=("$command_name: Instalacja i wymagania -> Zainstaluj narzedzia WSL")
          ;;
        docker)
          missing_actions+=("docker: zainstaluj Docker Engine i Compose recznie; Centrala nie instaluje Docker Desktop")
          ;;
        vault)
          missing_actions+=("vault: Instalacja i wymagania -> Zainstaluj Vault w WSL")
          ;;
        java|javac|keytool)
          missing_actions+=("$command_name: Instalacja i wymagania -> Sprawdz Java/JDK i keytool; zainstaluj JDK 25 albo ustaw JDK w IntelliJ")
          ;;
        python3)
          missing_actions+=("python3: zainstaluj Python 3 w systemie; jest potrzebny m.in. do generatora PDF")
          ;;
        *)
          missing_actions+=("$command_name: sprawdz instrukcje projektu")
          ;;
      esac
    fi
  done

  if command -v docker >/dev/null 2>&1; then
    if ! docker compose version 2>/dev/null; then
      printf '%-12s BRAK\n' 'docker compose'
      failures=$((failures + 1))
      missing_actions+=("docker compose: zainstaluj Docker Compose zgodnie z systemem; potem wybierz Instalacja i wymagania -> Sprawdz Docker Engine i Compose")
    fi
  fi

  if command -v python3 >/dev/null 2>&1; then
    if python3 -m venv --help >/dev/null 2>&1; then
      printf '%-12s OK (modul venv)\n' 'python3-venv'
    else
      printf '%-12s BRAK\n' 'python3-venv'
      printf 'Generator PDF wymaga jednorazowo: sudo apt update && sudo apt install -y python3-venv\n'
      failures=$((failures + 1))
      missing_actions+=("python3-venv: wykonaj podana komende w WSL, a potem ponow TLS i dokumenty generowane -> Generuj PDF regulaminu")
    fi
  fi

  printf '\nWersje obrazow Compose wymagane przez projekt:\n'
  printf 'PostgreSQL: postgres:18 (bank-postgres, shop-postgres)\n'
  printf 'Redis:      redis:7.4-alpine (bank-redis)\n'
  printf '\nDocker Compose nie instaluje baz przez apt. Uruchamia je z docker-compose.yaml.\n'

  if [[ ${#missing_actions[@]} -gt 0 ]]; then
    printf '\nDalsze kroki - wykryte braki:\n'
    for action in "${missing_actions[@]}"; do
      printf '  - %s\n' "$action"
    done
  else
    printf '\nBrak wymaganych komponentow. Mozesz przejsc do przygotowania profilu DEV lub PROD.\n'
  fi
  return "$failures"
}

install_wsl_tools() {
  if ! command -v apt-get >/dev/null 2>&1; then
    printf 'Automatyczna instalacja apt jest dostepna dla Ubuntu/Debian w WSL.\n'
    printf 'Na innym systemie zainstaluj jq, OpenSSL, GPG, curl i wget przez lokalny menedzer pakietow.\n'
    return 1
  fi
  command -v sudo >/dev/null 2>&1 || {
    printf 'Brak sudo. Zainstaluj narzedzia jako administrator systemu.\n'
    return 1
  }
  sudo apt-get update || return 1
  sudo apt-get install -y ca-certificates curl wget jq openssl gnupg || return 1
  printf 'Narzedzia WSL zostaly zainstalowane lub zaktualizowane.\n'
}

install_vault_wsl() {
  if command -v vault >/dev/null 2>&1; then
    printf 'Vault jest juz zainstalowany: %s\n' "$(vault version 2>/dev/null || true)"
    return 0
  fi
  if ! command -v apt-get >/dev/null 2>&1; then
    printf 'Automatyczna instalacja Vault jest przygotowana dla Ubuntu/Debian w WSL.\n'
    printf 'Na innym systemie uzyj oficjalnej instrukcji instalacji Vault.\n'
    return 1
  fi
  command -v sudo >/dev/null 2>&1 || {
    printf 'Brak sudo. Instalacja Vault wymaga uprawnien administratora.\n'
    return 1
  }
  command -v wget >/dev/null 2>&1 || {
    printf 'Brak wget. Najpierw uruchom instalacje narzedzi WSL.\n'
    return 1
  }
  command -v gpg >/dev/null 2>&1 || {
    printf 'Brak gpg. Najpierw uruchom instalacje narzedzi WSL.\n'
    return 1
  }

  wget -O - https://apt.releases.hashicorp.com/gpg \
    | sudo gpg --dearmor -o /usr/share/keyrings/hashicorp-archive-keyring.gpg || return 1
  printf 'deb [arch=%s signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com %s main\n' \
    "$(dpkg --print-architecture)" \
    "$(grep -oP '(?<=UBUNTU_CODENAME=).*' /etc/os-release 2>/dev/null || lsb_release -cs)" \
    | sudo tee /etc/apt/sources.list.d/hashicorp.list >/dev/null || return 1
  sudo apt-get update || return 1
  sudo apt-get install -y vault || return 1
  vault version
}

check_docker_engine() {
  local status=0
  if ! command -v docker >/dev/null 2>&1; then
    printf 'Docker CLI: BRAK\n'
    printf 'Zainstaluj Docker Engine i Compose zgodnie z instrukcja dla systemu.\n'
    return 1
  fi
  docker version --format 'Docker Engine: {{.Server.Version}}' 2>/dev/null || {
    printf 'Docker CLI jest dostepne, ale daemon nie odpowiada.\n'
    status=1
  }
  docker compose version || status=1
  return "$status"
}

check_java_toolchain() {
  local failures=0
  for command_name in java javac keytool; do
    if command -v "$command_name" >/dev/null 2>&1; then
      printf '%-8s %s\n' "$command_name" "$(command -v "$command_name")"
    elif windows_jdk_available; then
      printf '%-8s JDK Windows przez fallback\n' "$command_name"
    else
      printf '%-8s BRAK\n' "$command_name"
      failures=$((failures + 1))
    fi
  done
  if [[ $failures -gt 0 ]]; then
    printf 'Projekt wymaga JDK 25. IntelliJ moze uzyc JDK Windows, a Control Center ma fallback przez mvnw.cmd.\n'
  fi
  return "$failures"
}

install_stripe_cli() {
  if command -v stripe >/dev/null 2>&1; then
    printf 'Stripe CLI jest juz zainstalowane: %s\n' "$(stripe version 2>/dev/null || true)"
    return 0
  fi
  if ! command -v npm >/dev/null 2>&1; then
    printf 'Brak npm. Stripe CLI mozna zainstalowac przez Node.js 18+:\n'
    printf '  npm install -g @stripe/cli\n'
    printf 'Alternatywnie uruchom instalacje Stripe CLI zgodnie z %s.\n' "$MAIN_GUIDE_REL"
    return 1
  fi
  npm install -g @stripe/cli || return 1
  stripe version
}

stripe_cli_status() {
  require_command stripe || return 1
  stripe version
  printf '\nKonfiguracja Stripe CLI:\n'
  stripe config --list
  printf '\nProvider sklepu DEV: %s\n' "$(read_env_value "$DEV_ENV" SHOP_PAYMENT_PROVIDER)"
  printf 'Listener webhookow nie jest uruchamiany w tle. Zatrzymaj go Ctrl+C.\n'
}

stripe_cli_login() {
  require_command stripe || return 1
  printf 'Logowanie otworzy strone Stripe i wymaga potwierdzenia w przegladarce.\n'
  stripe login
}

stripe_webhook_listener() {
  require_command stripe || return 1
  local provider
  provider="$(read_env_value "$DEV_ENV" SHOP_PAYMENT_PROVIDER)"
  if [[ "$provider" != "stripe" ]]; then
    printf 'Uwaga: SHOP_PAYMENT_PROVIDER w %s ma wartosc "%s".\n' "$DEV_ENV" "${provider:-brak}"
    printf 'Listener jest potrzebny tylko dla provider=stripe.\n'
    confirm 'Uruchomic mimo to?' || return 0
  fi
  printf 'Webhooki beda przekazywane do http://localhost:8081/api/payments/stripe/webhook\n'
  printf 'Skopiuj whsec_... do SHOP_PAYMENT_WEBHOOK_SECRET w .novabank/env/dev.env, potem zrestartuj Shop DEV.\n'
  stripe listen --forward-to http://localhost:8081/api/payments/stripe/webhook
}

build_bank() {
  run_maven -Punit-tests clean package
}

build_shop() {
  run_maven -f "$PROJECT_ROOT/shop-app/pom.xml" -Punit-tests clean package
}

build_both() {
  build_bank || return 1
  build_shop
}

build_prod_artifacts() {
  run_maven -Pprod -DskipTests clean package || return 1
  run_maven -f "$PROJECT_ROOT/shop-app/pom.xml" -Pprod -DskipTests clean package
}

docker_menu() {
  while true; do
    section_header "$(t docker.title)"
    printf '%s\n' \
      "1. $(t docker.1)" \
      "2. $(t docker.2)" \
      "3. $(t docker.3)" \
      "4. $(t docker.4)" \
      "5. $(t docker.5)" \
      "6. $(t docker.6)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Status Docker, PostgreSQL i Redis" "docs/control-center.md" none docker_infrastructure_status ;;
      2) run_action "Start PostgreSQL i Redis" "docs/control-center.md" yes start_project_databases ;;
      3) run_action "Restart PostgreSQL i Redis" "docs/control-center.md" yes restart_project_databases ;;
      4) run_action "Stop PostgreSQL i Redis" "docs/control-center.md" yes stop_project_databases ;;
      5) run_action "Logi PostgreSQL i Redis" "docs/control-center.md" none project_database_logs ;;
      6) run_action "Walidacja docker-compose.yaml" "docs/control-center.md" none validate_project_compose ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

flyway_menu() {
  run_action "Diagnostyka migracji Flyway" "docs/control-center.md" none flyway_diagnostics
}

stripe_menu() {
  while true; do
    section_header "$(t stripe.title)"
    printf '%s\n' \
      "1. $(t stripe.1)" \
      "2. $(t stripe.2)" \
      "3. $(t stripe.3)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Status Stripe CLI" "docs/intellij-dev.md" none stripe_cli_status ;;
      2) run_action "Logowanie Stripe CLI" "docs/intellij-dev.md" yes stripe_cli_login ;;
      3) run_action "Listener webhookow Stripe" "docs/intellij-dev.md" yes stripe_webhook_listener ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

build_menu() {
  while true; do
    section_header "$(t build.title)"
    printf '%s\n' \
      "1. $(t build.1)" \
      "2. $(t build.2)" \
      "3. $(t build.3)" \
      "4. $(t build.4)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Build banku" "docs/ci-cd.md" yes build_bank ;;
      2) run_action "Build shop-app" "docs/ci-cd.md" yes build_shop ;;
      3) run_action "Build banku i shop-app" "docs/ci-cd.md" yes build_both ;;
      4) run_action "Build artefaktow PROD" "docs/ci-cd.md" prod build_prod_artifacts ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

redis_menu() {
  while true; do
    section_header "$(t redis.title)"
    printf '%s\n' \
      "1. $(t redis.1)" \
      "2. $(t redis.2)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Status sesji Redis" "docs/control-center.md" none redis_session_status ;;
      2) run_action "Uniewaznienie sesji banku" "docs/control-center.md" yes invalidate_bank_sessions ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

maintenance_menu() {
  while true; do
    section_header "$(t maintenance.title)"
    printf '%s\n' \
      "1. $(t maintenance.1)" \
      "2. $(t maintenance.2)" \
      "3. $(t maintenance.3)" \
      "4. $(t maintenance.4)" \
      "5. $(t maintenance.5)" \
      "6. $(t maintenance.6)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) docker_menu ;;
      2) flyway_menu ;;
      3) stripe_menu ;;
      4) build_menu ;;
      5) redis_menu ;;
      6) run_action "Porzadkowanie artefaktow lokalnych" "docs/control-center.md" yes local_artifact_cleanup ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

installation_menu() {
  while true; do
    section_header "$(t installation.title)"
    printf '%s\n' \
      "1. $(t installation.1)" \
      "2. $(t installation.2)" \
      "3. $(t installation.3)" \
      "4. $(t installation.4)" \
      "5. $(t installation.5)" \
      "6. $(t installation.6)" \
      "7. $(t installation.7)" \
      "8. $(t installation.8)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Kontrola wymaganych komponentow" "docs/General_Instruction.md" none check_installation_status ;;
      2) run_action "Instalacja Vault w WSL" "docs/VAULT-PROD-INSTRUKCJA.md" yes install_vault_wsl ;;
      3) run_action "Instalacja narzedzi WSL" "docs/General_Instruction.md" yes install_wsl_tools ;;
      4) run_action "Kontrola Docker Engine i Compose" "docs/General_Instruction.md" none check_docker_engine ;;
      5) run_action "Kontrola Java JDK i keytool" "docs/General_Instruction.md" none check_java_toolchain ;;
      6) run_action "Instalacja Stripe CLI" "docs/intellij-dev.md" yes install_stripe_cli ;;
      7) run_action "Uruchomienie PostgreSQL i Redis" "docs/General_Instruction.md" yes start_project_databases ;;
      8) run_action "Pelna kontrola zaleznosci" "docs/General_Instruction.md" none check_installation_status ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

environment_menu() {
  while true; do
    section_header "$(t environment.title)"
    printf '%s\n' \
      "1. $(t environment.1)" \
      "2. $(t environment.2)" \
      "3. $(t environment.3)" \
      "4. $(t environment.4)" \
      "5. $(t environment.5)" \
      "6. $(t environment.6)" \
      "7. $(t environment.7)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Przygotowanie DEV" "docs/intellij-dev.md" yes prepare_dev ;;
      2) run_action "Rotacja lokalnego klucza API" "docs/intellij-dev.md" yes prepare_dev_env true ;;
      3) run_action "Status infrastruktury" "docs/General_Instruction.md" none show_infrastructure_status ;;
      4) run_action "Start DEV poza IntelliJ" "docs/intellij-dev.md" yes run_powershell_script "$PROJECT_ROOT/scripts/start-dev.ps1" ;;
      5) run_action "Stop DEV poza IntelliJ" "docs/intellij-dev.md" yes run_powershell_script "$PROJECT_ROOT/scripts/stop-dev.ps1" -Confirm:\$false ;;
      6) run_action "Przygotowanie PROD" "docs/VAULT-PROD-INSTRUKCJA.md" prod prepare_prod ;;
      7) run_action "Konfiguracje IntelliJ" "docs/control-center.md" none show_intellij_configurations ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

monitoring_menu() {
  while true; do
    section_header "$(t monitoring.title)"
    printf '%s\n' \
      "1. $(t monitoring.1)" \
      "2. $(t monitoring.2)" \
      "3. $(t monitoring.3)" \
      "4. $(t monitoring.4)" \
      "5. $(t monitoring.5)" \
      "6. $(t monitoring.6)" \
      "7. $(t monitoring.7)" \
      "8. $(t monitoring.8)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Konfiguracja monitoringu DEV" "docs/monitoring-preparation.md" yes bash "$PROJECT_ROOT/scripts/monitoring/monitoring.sh" setup --profile dev ;;
      2) run_action "Start monitoringu" "docs/monitoring-preparation.md" yes bash "$PROJECT_ROOT/scripts/monitoring/monitoring.sh" start --profile dev ;;
      3) run_action "Status monitoringu" "docs/monitoring-preparation.md" none bash "$PROJECT_ROOT/scripts/monitoring/monitoring.sh" status --profile dev ;;
      4) run_action "Logi Prometheus" "docs/monitoring-preparation.md" none bash "$PROJECT_ROOT/scripts/monitoring/monitoring.sh" logs prometheus ;;
      5) run_action "Logi Grafana" "docs/monitoring-preparation.md" none bash "$PROJECT_ROOT/scripts/monitoring/monitoring.sh" logs grafana ;;
      6) run_action "Logi Alertmanager" "docs/monitoring-preparation.md" none bash "$PROJECT_ROOT/scripts/monitoring/monitoring.sh" logs alertmanager ;;
      7) run_action "Stop monitoringu" "docs/monitoring-preparation.md" yes bash "$PROJECT_ROOT/scripts/monitoring/monitoring.sh" stop --profile dev ;;
      8) run_action "Rotacja hasel monitoringu DEV" "docs/monitoring-preparation.md" yes bash "$PROJECT_ROOT/scripts/monitoring/monitoring.sh" setup --profile dev --force ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

backup_menu() {
  while true; do
    section_header "$(t backup.title)"
    printf '%s\n' \
      "1. $(t backup.1)" \
      "2. $(t backup.2)" \
      "3. $(t backup.3)" \
      "4. $(t backup.4)" \
      "5. $(t backup.5)" \
      "6. $(t backup.6)" \
      "7. $(t backup.7)" \
      "8. $(t backup.8)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Backup banku i sklepu" "docs/postgres-backup-restore.md" yes bash "$PROJECT_ROOT/scripts/backup/backup.sh" create ;;
      2) run_action "Test odtworzenia najnowszej kopii" "docs/postgres-backup-restore.md" yes bash "$PROJECT_ROOT/scripts/backup/backup.sh" restore-test ;;
      3) run_action "Backup z retencja" "docs/postgres-backup-restore.md" yes bash "$PROJECT_ROOT/scripts/backup/backup.sh" prune ;;
      4) run_action "Instalacja harmonogramu backupu" "docs/postgres-backup-restore.md" yes bash "$PROJECT_ROOT/scripts/backup/backup.sh" schedule-install ;;
      5) run_action "Status harmonogramu backupu" "docs/postgres-backup-restore.md" none bash "$PROJECT_ROOT/scripts/backup/backup.sh" schedule-status ;;
      6) run_action "Natychmiastowy zaplanowany backup" "docs/postgres-backup-restore.md" yes bash "$PROJECT_ROOT/scripts/backup/backup.sh" schedule-run-now ;;
      7) run_action "Logi harmonogramu backupu" "docs/postgres-backup-restore.md" none bash "$PROJECT_ROOT/scripts/backup/backup.sh" schedule-logs ;;
      8) run_action "Usuniecie harmonogramu backupu" "docs/postgres-backup-restore.md" yes bash "$PROJECT_ROOT/scripts/backup/backup.sh" schedule-remove ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

dr_menu() {
  while true; do
    section_header "$(t dr.title)"
    printf '%s\n' \
      "1. $(t dr.1)" \
      "2. $(t dr.2)" \
      "3. $(t dr.3)" \
      "4. $(t dr.4)" \
      "5. $(t dr.5)" \
      "6. $(t dr.6)" \
      "7. $(t dr.7)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Pelny test Disaster Recovery" "docs/postgres-backup-restore.md" yes bash "$PROJECT_ROOT/scripts/backup/backup.sh" dr-test ;;
      2) run_action "Instalacja harmonogramu DR" "docs/postgres-backup-restore.md" yes bash "$PROJECT_ROOT/scripts/backup/backup.sh" dr-schedule-install ;;
      3) run_action "Status harmonogramu DR" "docs/postgres-backup-restore.md" none bash "$PROJECT_ROOT/scripts/backup/backup.sh" dr-schedule-status ;;
      4) run_action "Natychmiastowy test DR" "docs/postgres-backup-restore.md" yes bash "$PROJECT_ROOT/scripts/backup/backup.sh" dr-schedule-run-now ;;
      5) run_action "Ostatni raport DR" "docs/postgres-backup-restore.md" none bash "$PROJECT_ROOT/scripts/backup/backup.sh" dr-report ;;
      6) run_action "Logi testow DR" "docs/postgres-backup-restore.md" none bash "$PROJECT_ROOT/scripts/backup/backup.sh" dr-schedule-logs ;;
      7) run_action "Usuniecie harmonogramu DR" "docs/postgres-backup-restore.md" yes bash "$PROJECT_ROOT/scripts/backup/backup.sh" dr-schedule-remove ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

vault_menu() {
  while true; do
    section_header "$(t vault.title)"
    printf '%s\n' \
      "1. $(t vault.1)" \
      "2. $(t vault.2)" \
      "3. $(t vault.3)" \
      "4. $(t vault.4)" \
      "5. $(t vault.5)" \
      "6. $(t vault.6)" \
      "7. $(t vault.7)" \
      "8. $(t vault.8)" \
      "9. $(t vault.9)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Status Vault" "docs/VAULT-PROD-INSTRUKCJA.md" none vault_status_action ;;
      2) run_action "Odpieczetowanie Vault" "docs/VAULT-PROD-INSTRUKCJA.md" yes vault_unseal_action ;;
      3) run_action "Konfiguracja AppRole PROD" "docs/VAULT-PROD-INSTRUKCJA.md" prod vault_operation vault_command bash "$PROJECT_ROOT/scripts/vault/setup-prod-approles.sh" ;;
      4) run_action "Rotacja Secret ID banku" "docs/VAULT-PROD-INSTRUKCJA.md" prod vault_operation vault_command bash "$PROJECT_ROOT/scripts/vault/approle-secret-id.sh" rotate bank ;;
      5) run_action "Rotacja Secret ID sklepu" "docs/VAULT-PROD-INSTRUKCJA.md" prod vault_operation vault_command bash "$PROJECT_ROOT/scripts/vault/approle-secret-id.sh" rotate shop ;;
      6) run_action "Rotacja Secret ID obu aplikacji" "docs/VAULT-PROD-INSTRUKCJA.md" prod vault_operation vault_command bash "$PROJECT_ROOT/scripts/vault/approle-secret-id.sh" rotate all ;;
      7) run_action "Finalizacja rotacji banku" "docs/VAULT-PROD-INSTRUKCJA.md" prod vault_operation vault_command bash "$PROJECT_ROOT/scripts/vault/approle-secret-id.sh" finalize bank ;;
      8) run_action "Finalizacja rotacji sklepu" "docs/VAULT-PROD-INSTRUKCJA.md" prod vault_operation vault_command bash "$PROJECT_ROOT/scripts/vault/approle-secret-id.sh" finalize shop ;;
      9) run_action "Finalizacja rotacji obu aplikacji" "docs/VAULT-PROD-INSTRUKCJA.md" prod vault_operation vault_command bash "$PROJECT_ROOT/scripts/vault/approle-secret-id.sh" finalize all ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

tools_menu() {
  while true; do
    section_header "$(t tools.title)"
    printf '%s\n' \
      "1. $(t tools.1)" \
      "2. $(t tools.2)" \
      "3. $(t tools.3)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Generowanie keystore TLS" "docs/VAULT-PROD-INSTRUKCJA.md" prod run_powershell_script "$PROJECT_ROOT/scripts/generate-prod-tls-keystores.ps1" ;;
      2) run_action "Ponowne generowanie keystore TLS" "docs/VAULT-PROD-INSTRUKCJA.md" prod run_powershell_script "$PROJECT_ROOT/scripts/generate-prod-tls-keystores.ps1" -Force ;;
      3) run_action "Generowanie PDF regulaminu" "docs/regulamin/README.md" yes generate_regulamin_pdf ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

tests_menu() {
  while true; do
    section_header "$(t tests.title)"
    printf '%s\n' \
      "1. $(t tests.1)" \
      "2. $(t tests.2)" \
      "3. $(t tests.3)" \
      "4. $(t tests.4)" \
      "5. $(t tests.5)" \
      "6. $(t tests.6)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Testy jednostkowe banku" "docs/ci-cd.md" yes run_maven -Punit-tests test ;;
      2) run_action "Testy jednostkowe sklepu" "docs/ci-cd.md" yes run_maven -f "$PROJECT_ROOT/shop-app/pom.xml" -Punit-tests test ;;
      3) run_action "Testy integracyjne banku" "docs/ci-cd.md" yes run_maven -Pintegration-tests verify ;;
      4) run_action "Testy integracyjne sklepu" "docs/ci-cd.md" yes run_maven -f "$PROJECT_ROOT/shop-app/pom.xml" -Pintegration-tests verify ;;
      5) run_action "Smoke E2E bank-sklep" "docs/ci-cd.md" yes run_e2e_smoke ;;
      6)
        run_action "Testy jednostkowe banku i sklepu" "docs/ci-cd.md" yes run_all_unit_tests
        ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

docs_menu() {
  while true; do
    section_header "$(t docs.title)"
    printf '%s\n' \
      "1. $(t docs.1)" \
      "2. $(t docs.2)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) run_action "Generalna instrukcja" "$MAIN_GUIDE_REL" none show_document "$PROJECT_ROOT/$MAIN_GUIDE_REL" ;;
      2) run_action "Interaktywne wprowadzenie" "$MAIN_GUIDE_REL" none launch_interactive_guide ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

language_menu() {
  while true; do
    section_header "$(t common.language)"
    printf '%s %s\n\n' "$(t common.current_language)" "$(is_english && t common.english || t common.polish)"
    printf '%s\n' \
      "1. $(t settings.polish_option)" \
      "2. $(t settings.english_option)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1|2)
        local selected_language="pl"
        [[ "$choice" == "2" ]] && selected_language="en"
        if set_language "$selected_language"; then
          printf '%s\n' "$(t common.language_saved)"
        else
          printf '%s\n' "$(t common.language_error)" >&2
        fi
        pause_menu
        ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

settings_menu() {
  while true; do
    section_header "$(t settings.title)"
    printf '%s\n' \
      "1. $(t settings.language_option)" \
      "0. $(t common.back)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || return
    case "$choice" in
      1) language_menu ;;
      0) return ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

print_catalog() {
  cat <<'EOF'
Pierwsze uruchomienie: check, .novabank/env/project.env, .novabank/env/dev.env, pliki PROD, monitoring
Srodowiska: prepare-dev, rotate-dev-key, status, start-dev, stop-dev, prepare-prod
Instalacja: check, vault, tools, docker, java, stripe, databases
Monitoring: setup, start, status, logs, stop, rotate-passwords
Backup: create, restore-test, prune, schedule-install/status/run/logs/remove
Disaster Recovery: dr-test, dr-report, dr-schedule-install/status/run/logs/remove
Vault: status, unseal, setup-approles, rotate/finalize bank/shop/all
TLS i dokumenty: prod-keystores, regulamin-pdf
Testy: unit, integration, e2e-smoke
Instrukcje: docs/General_Instruction.md oraz interaktywne wprowadzenie
Utrzymanie: Docker/PostgreSQL/Redis, Flyway, Stripe CLI, build, sesje Redis
Porzadkowanie: stare logi, raporty testowe i wygasle pliki tymczasowe
EOF
}

preflight() {
  local failures=0
  printf 'NovaBank Control Center - kontrola wymagan\n\n'
  print_platform_guidance
  printf '\n'
  for command in bash docker curl; do
    if command -v "$command" >/dev/null 2>&1; then
      printf '%-18s OK\n' "$command"
    else
      printf '%-18s BRAK\n' "$command"
      failures=$((failures + 1))
    fi
  done
  for file in \
    scripts/backup/backup.sh \
    scripts/monitoring/monitoring.sh \
    scripts/vault/approle-secret-id.sh \
    mvnw; do
    if [[ -f "$PROJECT_ROOT/$file" ]]; then
      printf '%-38s OK\n' "$file"
    else
      printf '%-38s BRAK\n' "$file"
      failures=$((failures + 1))
    fi
  done
  [[ $failures -eq 0 ]]
}

main_menu() {
  while true; do
    header
    printf '%s\n' \
      "1. $(t main.1)" \
      "2. $(t main.2)" \
      "3. $(t main.3)" \
      "4. $(t main.4)" \
      "5. $(t main.5)" \
      "6. $(t main.6)" \
      "7. $(t main.7)" \
      "8. $(t main.8)" \
      "9. $(t main.9)" \
      "10. $(t main.10)" \
      "11. $(t main.11)" \
      "12. $(t main.12)" \
      "13. $(t main.13)" \
      "14. $(t main.14)" \
      "0. $(t common.exit)"
    printf '\n%s ' "$(t common.choice)"
    local choice=""
    IFS= read -r choice || exit 0
    case "$choice" in
      1) first_run_menu ;;
      2) environment_menu ;;
      3) installation_menu ;;
      4) monitoring_menu ;;
      5) backup_menu ;;
      6) dr_menu ;;
      7) vault_menu ;;
      8) tools_menu ;;
      9) tests_menu ;;
      10) docs_menu ;;
      11) run_action "Interaktywne wprowadzenie" "docs/General_Instruction.md" none launch_interactive_guide ;;
      12) run_action "Diagnostyka calego srodowiska" "docs/control-center.md" none show_infrastructure_status ;;
      13) maintenance_menu ;;
      14) settings_menu ;;
      0|exit|quit) exit 0 ;;
      *) printf '%s\n' "$(t common.invalid)"; sleep 1 ;;
    esac
  done
}

case "${1:-}" in
  --list)
    print_catalog
    ;;
  --check)
    preflight
    ;;
  --help|-h)
    printf 'Uzycie: bash scripts/control-center/control-center.sh [--list|--check]\n'
    ;;
  "")
    main_menu
    ;;
  *)
    printf 'Nieznana opcja: %s\n' "$1" >&2
    exit 2
    ;;
esac
