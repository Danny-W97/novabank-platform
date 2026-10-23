#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOCAL_ROOT="$PROJECT_ROOT/.novabank"
ENV_DIR="$LOCAL_ROOT/env"
MONITORING_DIR="$LOCAL_ROOT/monitoring"

PROFILE="dev"
COMMAND=""
FORCE=false
LOG_SERVICE=""

MONITORING_ENV="$MONITORING_DIR/.env.monitoring"
MONITORING_ENV_EXAMPLE="$PROJECT_ROOT/infra/monitoring/.env.monitoring.example"
DEV_ENV="$ENV_DIR/dev.env"
PROJECT_ENV="$ENV_DIR/project.env"
PROMETHEUS_CONFIG="$MONITORING_DIR/prometheus.yml"
PROMETHEUS_EXAMPLE="$PROJECT_ROOT/infra/monitoring/prometheus/prometheus.yml.example"
COMPOSE_FILE="$PROJECT_ROOT/docker-compose.monitoring.yaml"

usage() {
  cat <<'EOF'
NovaBank - obsluga lokalnego monitoringu

Uzycie:
  bash scripts/monitoring/monitoring.sh setup [--profile dev] [--force]
  bash scripts/monitoring/monitoring.sh start [--profile dev]
  bash scripts/monitoring/monitoring.sh status [--profile dev]
  bash scripts/monitoring/monitoring.sh logs [prometheus|grafana|alertmanager]
  bash scripts/monitoring/monitoring.sh stop [--profile dev]

Polecenia:
  setup   Generuje lub uzupelnia lokalne sekrety i konfiguracje dev.
  start   Uruchamia stack Compose i sprawdza gotowosc oraz Targets=UP.
  status  Pokazuje kontenery, endpointy gotowosci i stan targetow.
  logs    Pokazuje ostatnie logi calego stacku albo wybranej uslugi.
  stop    Zatrzymuje monitoring bez usuwania wolumenow z danymi.

Opcje:
  --profile dev  Profil konfiguracji. Prod jest zarezerwowany dla Vault Agent.
  --force        Rotuje lokalne haslo metryk i haslo administratora Grafany.
EOF
}

fail() {
  printf 'BLAD: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '%s\n' "$*"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    setup|start|status|logs|stop)
      [[ -z "$COMMAND" ]] || fail "Podano wiecej niz jedno polecenie."
      COMMAND="$1"
      shift
      ;;
    --profile)
      [[ $# -ge 2 ]] || fail "Brak wartosci po --profile."
      PROFILE="$2"
      shift 2
      ;;
    --force)
      FORCE=true
      shift
      ;;
    prometheus|grafana|alertmanager)
      [[ "$COMMAND" == "logs" ]] || fail "Nazwe uslugi mozna podac tylko dla logs."
      LOG_SERVICE="$1"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Nieznany argument: $1"
      ;;
  esac
done

[[ -n "$COMMAND" ]] || {
  usage
  exit 1
}

if [[ "$PROFILE" != "dev" ]]; then
  fail "Profil '$PROFILE' nie jest jeszcze obslugiwany. Dla prod zostanie dodana integracja Vault Agent bez lokalnego generowania sekretow."
fi

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Brak polecenia '$1'. Zainstaluj je w systemie i ponow operacje."
}

read_env_value() {
  local file="$1"
  local key="$2"
  [[ -f "$file" ]] || return 0
  awk -v key="$key" '
    index($0, key "=") == 1 {
      value = substr($0, length(key) + 2)
      sub(/\r$/, "", value)
      print value
      exit
    }
  ' "$file"
}

is_valid_secret() {
  local value="${1:-}"
  [[ -n "$value" ]] || return 1
  case "$value" in
    CHANGE_ME|change-me-*|replace-with-*|\<*\>) return 1 ;;
  esac
  return 0
}

generate_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
    return
  fi

  require_command od
  od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
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
    index($0, key "=") == 1 {
      if (!replaced) {
        print key "=" value
        replaced = 1
      }
      next
    }
    { sub(/\r$/, ""); print }
    END {
      if (!replaced) print key "=" value
    }
  ' "$file" > "$temp"

  mv "$temp" "$file"
}

render_prometheus_config() {
  local username="$1"
  local password="$2"
  local bank_port shop_port
  bank_port="$(read_env_value "$DEV_ENV" BANK_MANAGEMENT_PORT)"
  [[ -n "$bank_port" ]] || bank_port="$(read_env_value "$PROJECT_ENV" BANK_MANAGEMENT_PORT)"
  shop_port="$(read_env_value "$DEV_ENV" SHOP_MANAGEMENT_PORT)"
  [[ -n "$shop_port" ]] || shop_port="$(read_env_value "$PROJECT_ENV" SHOP_MANAGEMENT_PORT)"
  bank_port="${bank_port:-8082}"; shop_port="${shop_port:-8083}"
  [[ "$bank_port" =~ ^[0-9]{4,5}$ && "$shop_port" =~ ^[0-9]{4,5}$ ]] || fail 'Niepoprawne porty Actuator.'
  local source="$PROMETHEUS_CONFIG"
  local temp

  if [[ ! -f "$source" ]]; then
    [[ -f "$PROMETHEUS_EXAMPLE" ]] || fail "Brak szablonu: $PROMETHEUS_EXAMPLE"
    source="$PROMETHEUS_EXAMPLE"
  fi

  temp="$(mktemp "${PROMETHEUS_CONFIG}.tmp.XXXXXX")"
  awk -v username="$username" -v password="$password" -v bank_port="$bank_port" -v shop_port="$shop_port" '
    function yaml_string(value) { gsub(/\047/, "\047\047", value); return "\047" value "\047" }
    /job_name:[[:space:]]*bank-demo/ { target_port = bank_port }
    /job_name:[[:space:]]*shop-app/ { target_port = shop_port }
    /^[[:space:]]*- targets:/ && target_port != "" { sub(/localhost:[0-9]+/, "localhost:" target_port) }
    /^[[:space:]]*username:[[:space:]]*/ {
      match($0, /^[[:space:]]*/)
      print substr($0, 1, RLENGTH) "username: " yaml_string(username)
      next
    }
    /^[[:space:]]*password:[[:space:]]*/ {
      match($0, /^[[:space:]]*/)
      print substr($0, 1, RLENGTH) "password: " yaml_string(password)
      next
    }
    { sub(/\r$/, ""); gsub(/CHANGE_ME/, password); print }
  ' "$source" > "$temp"

  mv "$temp" "$PROMETHEUS_CONFIG"
}

validate_configuration() {
  local file key value

  for file in "$MONITORING_ENV" "$DEV_ENV" "$PROMETHEUS_CONFIG"; do
    [[ -s "$file" ]] || fail "Brak wymaganej konfiguracji: $file. Uruchom najpierw setup."
  done

  for key in GRAFANA_ADMIN_PASSWORD METRICS_USERNAME METRICS_PASSWORD; do
    value="$(read_env_value "$MONITORING_ENV" "$key")"
    [[ -n "$value" ]] || fail "Brak $key w $MONITORING_ENV."
  done

  for key in METRICS_USERNAME METRICS_PASSWORD MANAGEMENT_SERVER_ADDRESS; do
    value="$(read_env_value "$DEV_ENV" "$key")"
    [[ -n "$value" ]] || fail "Brak $key w $DEV_ENV."
  done

  if grep -Eq 'CHANGE_ME|change-me-before' "$MONITORING_ENV" "$PROMETHEUS_CONFIG"; then
    fail "Konfiguracja nadal zawiera wartosc przykladowa. Uruchom setup."
  fi
}

setup_dev() {
  mkdir -p "$ENV_DIR" "$MONITORING_DIR" || fail 'Nie można utworzyć katalogów konfiguracji monitoringu DEV.'
  local dev_metrics monitoring_metrics metrics_password
  local metrics_username grafana_user grafana_password

  [[ -f "$MONITORING_ENV_EXAMPLE" ]] || fail "Brak pliku wzorcowego: $MONITORING_ENV_EXAMPLE"

  dev_metrics="$(read_env_value "$DEV_ENV" METRICS_PASSWORD)"
  [[ -n "$dev_metrics" ]] || dev_metrics="$(read_env_value "$PROJECT_ENV" METRICS_PASSWORD)"
  monitoring_metrics="$(read_env_value "$MONITORING_ENV" METRICS_PASSWORD)"

  if [[ "$FORCE" == true ]]; then
    metrics_password="$(generate_secret)"
  elif is_valid_secret "$dev_metrics" && is_valid_secret "$monitoring_metrics" && [[ "$dev_metrics" != "$monitoring_metrics" ]]; then
    fail "Hasla METRICS_PASSWORD w .novabank/env/dev.env i .novabank/monitoring/.env.monitoring sa rozne. Ujednolic je recznie albo uruchom setup --force, aby bezpiecznie obrocic oba."
  elif is_valid_secret "$dev_metrics"; then
    metrics_password="$dev_metrics"
  elif is_valid_secret "$monitoring_metrics"; then
    metrics_password="$monitoring_metrics"
  else
    metrics_password="$(generate_secret)"
  fi

  metrics_username="$(read_env_value "$DEV_ENV" METRICS_USERNAME)"
  [[ -n "$metrics_username" ]] || metrics_username="$(read_env_value "$PROJECT_ENV" METRICS_USERNAME)"
  [[ -n "$metrics_username" ]] || metrics_username="$(read_env_value "$MONITORING_ENV" METRICS_USERNAME)"
  [[ -n "$metrics_username" ]] || metrics_username="metrics-reader"

  grafana_user="$(read_env_value "$MONITORING_ENV" GRAFANA_ADMIN_USER)"
  [[ -n "$grafana_user" ]] || grafana_user="admin"

  grafana_password="$(read_env_value "$MONITORING_ENV" GRAFANA_ADMIN_PASSWORD)"
  if [[ "$FORCE" == true ]] || ! is_valid_secret "$grafana_password"; then
    grafana_password="$(generate_secret)"
  fi

  upsert_env_value "$DEV_ENV" METRICS_USERNAME "$metrics_username"
  upsert_env_value "$DEV_ENV" METRICS_PASSWORD "$metrics_password"
  upsert_env_value "$DEV_ENV" MANAGEMENT_SERVER_ADDRESS "0.0.0.0"
  # DEV owns metrics credentials; project.env contains infrastructure addresses only.

  if [[ ! -f "$MONITORING_ENV" ]]; then
    cp "$MONITORING_ENV_EXAMPLE" "$MONITORING_ENV"
  fi
  upsert_env_value "$MONITORING_ENV" GRAFANA_ADMIN_USER "$grafana_user"
  upsert_env_value "$MONITORING_ENV" GRAFANA_ADMIN_PASSWORD "$grafana_password"
  upsert_env_value "$MONITORING_ENV" METRICS_USERNAME "$metrics_username"
  upsert_env_value "$MONITORING_ENV" METRICS_PASSWORD "$metrics_password"
  upsert_env_value "$MONITORING_ENV" MANAGEMENT_SERVER_ADDRESS "0.0.0.0"

  render_prometheus_config "$metrics_username" "$metrics_password"
  chmod 600 "$DEV_ENV" "$MONITORING_ENV" "$PROMETHEUS_CONFIG" 2>/dev/null || true
  validate_configuration

  info "Konfiguracja monitoringu dev jest gotowa."
  info "Zsynchronizowano dane techniczne bez wyswietlania sekretow:"
  info "  - .novabank/env/dev.env"
  info "  - .novabank/monitoring/.env.monitoring"
  info "  - .novabank/monitoring/prometheus.yml"
  if [[ "$FORCE" == true ]]; then
    info "Wykonano rotacje lokalnych hasel. Uruchom ponownie bank i shop-app."
  else
    info "Po pierwszym setup uruchom ponownie bank i shop-app, aby wczytaly wspolne haslo metryk."
  fi
  info "Haslo Grafany odczytasz lokalnie z .novabank/monitoring/.env.monitoring. Nie dodawaj tego pliku do Git."
  if [[ ${#metrics_password} -lt 24 || ${#grafana_password} -lt 24 ]]; then
    info "OSTRZEZENIE: zachowano istniejace haslo krotsze niz 24 znaki. Zaplanuj rotacje poleceniem setup --force."
  fi
}

compose() {
  docker compose \
    --env-file "$MONITORING_ENV" \
    -f "$COMPOSE_FILE" \
    "$@"
}

reload_prometheus_config() {
  if curl -fsS --max-time 5 -X POST http://localhost:9090/-/reload >/dev/null 2>&1; then
    info "OK: Prometheus przeladowal konfiguracje."
    return 0
  fi

  info "Prometheus nie przyjal przeladowania HTTP. Restartuje kontener, aby wczytac konfiguracje."
  compose restart prometheus
}

wait_for_url() {
  local name="$1"
  local url="$2"
  local attempts="${3:-30}"
  local index

  for ((index = 1; index <= attempts; index++)); do
    if curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
      printf 'OK: %s odpowiada (%s).\n' "$name" "$url"
      return 0
    fi
    sleep 2
  done

  printf 'BLAD: %s nie odpowiada pod %s.\n' "$name" "$url" >&2
  return 1
}

print_targets() {
  local payload="$1"
  printf '%s' "$payload" | jq -r '
    .data.activeTargets[]
    | select(.labels.job == "bank-demo" or .labels.job == "shop-app")
    | "  \(.labels.job): \(.health | ascii_upcase) - \(.scrapeUrl)\(if .lastError == "" then "" else " - " + .lastError end)"
  '
}

check_targets_once() {
  local payload expected up
  payload="$(curl -fsS --max-time 5 http://localhost:9090/api/v1/targets)" || return 1
  expected="$(printf '%s' "$payload" | jq '[.data.activeTargets[] | select(.labels.job == "bank-demo" or .labels.job == "shop-app")] | length')"
  up="$(printf '%s' "$payload" | jq '[.data.activeTargets[] | select((.labels.job == "bank-demo" or .labels.job == "shop-app") and .health == "up")] | length')"
  [[ "$expected" -eq 2 && "$up" -eq 2 ]]
}

check_targets() {
  local wait_mode="${1:-false}"
  local attempts=1 index payload
  require_command jq

  [[ "$wait_mode" == true ]] && attempts=20
  for ((index = 1; index <= attempts; index++)); do
    if check_targets_once; then
      payload="$(curl -fsS --max-time 5 http://localhost:9090/api/v1/targets)"
      info "Targety Prometheusa:"
      print_targets "$payload"
      info "OK: bank-demo i shop-app maja stan UP."
      return 0
    fi
    [[ "$index" -lt "$attempts" ]] && sleep 3
  done

  payload="$(curl -fsS --max-time 5 http://localhost:9090/api/v1/targets 2>/dev/null || true)"
  info "Targety Prometheusa:"
  if [[ -n "$payload" ]]; then
    print_targets "$payload" || true
    if printf '%s' "$payload" | grep -q 'status code 401'; then
      printf 'Wskazowka: aplikacja odrzuca dane METRICS_USERNAME/METRICS_PASSWORD. Po zmianie .novabank/env/dev.env zatrzymaj i uruchom ponownie bank oraz shop-app w IntelliJ.\n' >&2
    fi
  else
    info "  Brak odpowiedzi API Prometheusa."
  fi
  printf 'BLAD: nie wszystkie wymagane targety maja stan UP. Sprawdz aplikacje na portach 8082 i 8083.\n' >&2
  return 1
}

show_status() {
  local result=0
  require_command docker
  require_command curl
  validate_configuration

  info "Kontenery monitoringu:"
  compose ps || result=1
  info ""
  wait_for_url "Prometheus" "http://localhost:9090/-/ready" 1 || result=1
  wait_for_url "Grafana" "http://localhost:3000/api/health" 1 || result=1
  wait_for_url "Alertmanager" "http://localhost:9093/-/ready" 1 || result=1
  check_targets false || result=1

  return "$result"
}

case "$COMMAND" in
  setup)
    setup_dev
    ;;
  start)
    require_command docker
    require_command curl
    require_command jq
    # Keep Prometheus, Grafana and both Spring Boot services on one dev secret.
    # A running IntelliJ process still needs a restart after this synchronization.
    setup_dev >/dev/null
    validate_configuration
    info "Uruchamianie Prometheusa, Grafany i Alertmanagera..."
    compose up -d
    wait_for_url "Prometheus" "http://localhost:9090/-/ready"
    reload_prometheus_config
    wait_for_url "Grafana" "http://localhost:3000/api/health"
    wait_for_url "Alertmanager" "http://localhost:9093/-/ready"
    check_targets true
    info "Panele: Prometheus http://localhost:9090 | Grafana http://localhost:3000 | Alertmanager http://localhost:9093"
    ;;
  status)
    show_status
    ;;
  logs)
    require_command docker
    validate_configuration
    if [[ -n "$LOG_SERVICE" ]]; then
      compose logs --tail=150 "$LOG_SERVICE"
    else
      compose logs --tail=150
    fi
    ;;
  stop)
    require_command docker
    validate_configuration
    compose down
    info "Monitoring zatrzymany. Wolumeny Prometheusa, Grafany i Alertmanagera pozostaly zachowane."
    ;;
esac
