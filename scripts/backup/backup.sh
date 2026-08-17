#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$REPO_DIR/.novabank/env/project.env}"
if [[ ! -f "$ENV_FILE" && -f "$REPO_DIR/.env" ]]; then
  ENV_FILE="$REPO_DIR/.env"
fi

CALLER_BACKUP_DIR="${BACKUP_DIR:-}"
CALLER_BANK_DUMP="${BANK_DUMP:-}"
CALLER_SHOP_DUMP="${SHOP_DUMP:-}"
CALLER_BANK_CONTAINER="${BANK_POSTGRES_CONTAINER:-}"
CALLER_SHOP_CONTAINER="${SHOP_POSTGRES_CONTAINER:-}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

detect_default_backup_dir() {
  if [[ -n "${WSL_DISTRO_NAME:-}" && -d /mnt/e ]]; then
    printf '%s\n' "/mnt/e/Bank-demo-backups/postgres"
  else
    printf '%s\n' "$(dirname "$REPO_DIR")/Bank-demo-backups/postgres"
  fi
}

DEFAULT_BACKUP_DIR="${DEFAULT_BACKUP_DIR:-$(detect_default_backup_dir)}"
CONFIGURED_BACKUP_DIR="${CALLER_BACKUP_DIR:-${BACKUP_DIR:-}}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
BACKUP_DELETE_OLD="${BACKUP_DELETE_OLD:-false}"
BANK_POSTGRES_CONTAINER="${CALLER_BANK_CONTAINER:-${BANK_POSTGRES_CONTAINER:-bank-postgres}}"
SHOP_POSTGRES_CONTAINER="${CALLER_SHOP_CONTAINER:-${SHOP_POSTGRES_CONTAINER:-shop-postgres}}"
ACTIVE_LOCK_DIR=""
SYSTEMD_UNIT_NAME="novabank-backup"
LAUNCHD_LABEL="com.novabank.backup"
DR_SYSTEMD_UNIT_NAME="novabank-dr-test"
DR_LAUNCHD_LABEL="com.novabank.dr-test"
DR_NETWORK=""
DR_LOG_DIR=""
DR_CONTAINERS=()

cleanup_disaster_environment() {
  local container
  if command -v docker >/dev/null 2>&1; then
    if [[ -n "$DR_LOG_DIR" && -d "$DR_LOG_DIR" ]]; then
      for container in "${DR_CONTAINERS[@]:-}"; do
        [[ -n "$container" ]] || continue
        docker logs "$container" > "$DR_LOG_DIR/${container}.log" 2>&1 || true
      done
    fi
    for container in "${DR_CONTAINERS[@]:-}"; do
      [[ -n "$container" ]] || continue
      docker rm -f "$container" >/dev/null 2>&1 || true
    done
    if [[ -n "$DR_NETWORK" ]]; then
      docker network rm "$DR_NETWORK" >/dev/null 2>&1 || true
    fi
  fi
  DR_CONTAINERS=()
  DR_NETWORK=""
  DR_LOG_DIR=""
}

cleanup_active_lock() {
  cleanup_disaster_environment
  if [[ -n "$ACTIVE_LOCK_DIR" && -d "$ACTIVE_LOCK_DIR" ]]; then
    rmdir "$ACTIVE_LOCK_DIR" 2>/dev/null || true
  fi
}
trap cleanup_active_lock EXIT INT TERM

usage() {
  cat <<'EOF'
Uzycie:
  bash scripts/backup/backup.sh
  bash scripts/backup/backup.sh create [opcje]
  bash scripts/backup/backup.sh restore-test [--backup-dir KATALOG]
  bash scripts/backup/backup.sh dr-test [--backup-dir KATALOG]
  bash scripts/backup/backup.sh dr-report [--backup-dir KATALOG]
  bash scripts/backup/backup.sh prune [--backup-dir KATALOG]
  bash scripts/backup/backup.sh schedule-install [opcje]
  bash scripts/backup/backup.sh schedule-status
  bash scripts/backup/backup.sh schedule-run-now
  bash scripts/backup/backup.sh schedule-logs
  bash scripts/backup/backup.sh schedule-remove
  bash scripts/backup/backup.sh dr-schedule-install [opcje]
  bash scripts/backup/backup.sh dr-schedule-status
  bash scripts/backup/backup.sh dr-schedule-run-now
  bash scripts/backup/backup.sh dr-schedule-logs
  bash scripts/backup/backup.sh dr-schedule-remove

Polecenia:
  bez argumentow          otworz osobny terminal na Linux/macOS lub wykonaj backup w WSL
  create                  utworz i sprawdz kompletna kopie obu baz
  restore-test            testowo odtworz najnowsza kompletna kopie
  dr-test                 pelny test awaryjny w izolowanych kontenerach
  dr-report               pokaz ostatni raport testu awaryjnego
  prune                   utworz nowa kopie, sprawdz ja i usun stare katalogi
  schedule-install        zainstaluj dzienny harmonogram systemd/launchd
  schedule-status         pokaz stan harmonogramu
  schedule-run-now        uruchom zaplanowany backup natychmiast
  schedule-logs           pokaz ostatnie logi harmonogramu
  schedule-remove         usun harmonogram z systemu
  dr-schedule-install     zainstaluj cotygodniowy test awaryjny
  dr-schedule-status      pokaz stan harmonogramu testu awaryjnego
  dr-schedule-run-now     uruchom test awaryjny natychmiast
  dr-schedule-logs        pokaz ostatnie logi testu awaryjnego
  dr-schedule-remove      usun harmonogram testu awaryjnego

Opcje create:
  --backup-dir KATALOG    absolutny katalog bazowy kopii
  --skip-restore-test     pomin testowe odtworzenie
  --prune                 po sukcesie usun kopie starsze niz okres retencji

Opcje schedule-install:
  --daily-at HH:MM        lokalna godzina wykonania, domyslnie 02:00
  --backup-dir KATALOG    absolutny katalog bazowy kopii
  --dry-run               wyswietl konfiguracje bez instalowania

Opcje dr-schedule-install:
  --weekday DZIEN         dzien tygodnia: MON..SUN, domyslnie SUN
  --at HH:MM              lokalna godzina wykonania, domyslnie 04:00
  --backup-dir KATALOG    absolutny katalog bazowy kopii
  --dry-run               wyswietl konfiguracje bez instalowania

Skrypt obsluguje WSL, Linux i macOS. Windows uruchamia go przez start.ps1.
EOF
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Brak wymaganej komendy: $1" >&2
    return 1
  }
}

checksum_write() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1"
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1"
  else
    echo "Brak sha256sum lub shasum." >&2
    return 1
  fi
}

checksum_verify() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c "$@"
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -c "$@"
  else
    echo "Brak sha256sum lub shasum." >&2
    return 1
  fi
}

file_mtime_epoch() {
  stat -c %Y "$1" 2>/dev/null || stat -f %m "$1"
}

latest_dump_in_dir() {
  local prefix="$1" directory="$2" candidate latest=""
  shopt -s nullglob
  for candidate in "$directory"/"${prefix}"_*.dump; do
    [[ -f "$candidate" ]] || continue
    if [[ -z "$latest" || "$candidate" -nt "$latest" ]]; then
      latest="$candidate"
    fi
  done
  shopt -u nullglob
  printf '%s\n' "$latest"
}

latest_complete_run() {
  local base_dir="$1" directory name bank_dump shop_dump best=""
  shopt -s nullglob
  for directory in "$base_dir"/????????T??????Z; do
    [[ -d "$directory" ]] || continue
    name="$(basename "$directory")"
    [[ "$name" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || continue
    bank_dump="$(latest_dump_in_dir bank "$directory")"
    shop_dump="$(latest_dump_in_dir shop "$directory")"
    if [[ -n "$bank_dump" && -n "$shop_dump" \
      && -f "${bank_dump}.sha256" && -f "${shop_dump}.sha256" \
      && ( -z "$best" || "$directory" > "$best" ) ]]; then
      best="$directory"
    fi
  done
  shopt -u nullglob
  [[ -n "$best" ]] || return 1
  printf '%s\n' "$best"
}

latest_dr_report() {
  local base_dir="$1" candidate latest=""
  [[ -d "$base_dir/disaster-recovery" ]] || return 1
  while IFS= read -r candidate; do
    [[ -f "$candidate" ]] || continue
    if [[ -z "$latest" || "$candidate" -nt "$latest" ]]; then
      latest="$candidate"
    fi
  done < <(find "$base_dir/disaster-recovery" -type f -name dr-report.log -print 2>/dev/null)
  [[ -n "$latest" ]] || return 1
  printf '%s\n' "$latest"
}

dr_report_command() {
  local selected_backup_dir="" report_file
  while (($# > 0)); do
    case "$1" in
      --backup-dir)
        [[ $# -ge 2 ]] || { echo "Brak wartosci dla --backup-dir." >&2; return 2; }
        selected_backup_dir="$2"
        shift 2
        ;;
      --help|-h)
        usage
        return 0
        ;;
      *)
        echo "Nieznany argument dr-report: $1" >&2
        return 2
        ;;
    esac
  done

  selected_backup_dir="${selected_backup_dir:-${CONFIGURED_BACKUP_DIR:-$DEFAULT_BACKUP_DIR}}"
  [[ "$selected_backup_dir" == /* ]] || {
    echo "Katalog backupu musi byc sciezka absolutna." >&2
    return 2
  }
  report_file="$(latest_dr_report "$selected_backup_dir" || true)"
  [[ -n "$report_file" ]] || {
    echo "Brak raportow DR w $selected_backup_dir/disaster-recovery." >&2
    echo "Uruchom najpierw: bash scripts/backup/backup.sh dr-test --backup-dir $selected_backup_dir" >&2
    return 1
  }
  printf 'Ostatni raport DR: %s\n\n' "$report_file"
  cat "$report_file"
}

select_backup_dir() {
  local requested="$1" prompt_default
  if [[ -z "$requested" ]]; then
    if [[ -t 0 ]]; then
      prompt_default="${CONFIGURED_BACKUP_DIR:-$DEFAULT_BACKUP_DIR}"
      [[ "$prompt_default" == /* ]] || prompt_default="$DEFAULT_BACKUP_DIR"
      read -r -p "Podaj katalog zapisu kopii [$prompt_default]: " requested
      requested="${requested:-$prompt_default}"
    elif [[ -n "$CONFIGURED_BACKUP_DIR" ]]; then
      requested="$CONFIGURED_BACKUP_DIR"
    else
      echo "Brak interaktywnego terminala. Uzyj --backup-dir albo ustaw BACKUP_DIR." >&2
      return 2
    fi
  fi

  if [[ "$requested" != /* ]]; then
    echo "Katalog musi byc sciezka absolutna, np. /mnt/e/backups, /srv/backups lub /Volumes/Backup." >&2
    return 2
  fi
  if [[ "$requested" == "/" || "$requested" == "$REPO_DIR" ]]; then
    echo "Wybrano niedozwolony katalog zapisu: $requested" >&2
    return 2
  fi
  printf '%s\n' "$requested"
}

wait_for_postgres() {
  local container="$1" user="$2" database="$3" password="$4" attempt=0
  while (( attempt < 30 )); do
    if docker exec -e PGPASSWORD="$password" "$container" pg_isready -U "$user" -d "$database" >/dev/null 2>&1; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 2
  done
  echo "PostgreSQL nie odpowiada: $container" >&2
  return 1
}

dump_database() {
  local name="$1" container="$2" user="$3" database="$4" password="$5" output_dir="$6" stamp="$7"
  local output="$output_dir/${name}_${stamp}.dump"
  local temporary="${output}.part"

  wait_for_postgres "$container" "$user" "$database" "$password" || return $?
  echo "Tworzenie backupu $name: $output"
  docker exec -e PGPASSWORD="$password" "$container" \
    pg_dump -U "$user" -d "$database" \
    --format=custom --no-owner --no-privileges > "$temporary" || {
      rm -f "$temporary"
      return 1
    }
  if [[ ! -s "$temporary" ]]; then
    echo "Pusty backup: $temporary" >&2
    rm -f "$temporary"
    return 1
  fi
  mv "$temporary" "$output"
  checksum_write "$output" > "${output}.sha256"
  docker exec -i "$container" pg_restore --list < "$output" >/dev/null
  echo "OK: $(du -h "$output" | cut -f1), suma: ${output}.sha256"
}

validate_database_configuration() {
  if [[ -z "${POSTGRES_DB:-}" || -z "${POSTGRES_USER:-}" || -z "${POSTGRES_PASSWORD:-}" ]]; then
    echo "Brak POSTGRES_DB, POSTGRES_USER lub POSTGRES_PASSWORD." >&2
    return 1
  fi
  if [[ -z "${SHOP_POSTGRES_DB:-}" || -z "${SHOP_POSTGRES_USER:-}" || -z "${SHOP_POSTGRES_PASSWORD:-}" ]]; then
    echo "Brak SHOP_POSTGRES_DB, SHOP_POSTGRES_USER lub SHOP_POSTGRES_PASSWORD." >&2
    return 1
  fi
}

drop_database() {
  local container="$1" user="$2" password="$3" database="$4"
  docker exec -e PGPASSWORD="$password" "$container" psql -U "$user" -d postgres \
    -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$database' AND pid <> pg_backend_pid();" >/dev/null 2>&1 || true
  docker exec -e PGPASSWORD="$password" "$container" psql -U "$user" -d postgres \
    -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS \"$database\";" >/dev/null 2>&1 || true
}

create_database() {
  local container="$1" user="$2" password="$3" database="$4"
  docker exec -e PGPASSWORD="$password" "$container" psql -U "$user" -d postgres \
    -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$database\" OWNER \"$user\";" >/dev/null
}

restore_database() {
  local container="$1" user="$2" password="$3" database="$4" dump="$5"
  echo "Odtwarzanie testowe $dump -> $database"
  create_database "$container" "$user" "$password" "$database" || return $?
  docker exec -i -e PGPASSWORD="$password" "$container" pg_restore \
    --exit-on-error --no-owner --no-privileges -U "$user" -d "$database" < "$dump" || return $?
  docker exec -e PGPASSWORD="$password" "$container" psql -U "$user" -d "$database" \
    -v ON_ERROR_STOP=1 -c "SELECT current_database(), count(*) AS flyway_migrations FROM flyway_schema_history;"
}

restore_test_databases() {
  local bank_dump="$1" shop_dump="$2"
  local stamp bank_restore_db shop_restore_db result=0
  stamp="$(date -u +%Y%m%d%H%M%S)"
  bank_restore_db="bank_restore_${stamp}"
  shop_restore_db="shop_restore_${stamp}"

  restore_database "$BANK_POSTGRES_CONTAINER" "$POSTGRES_USER" "$POSTGRES_PASSWORD" \
    "$bank_restore_db" "$bank_dump" || result=$?
  if [[ $result -eq 0 ]]; then
    restore_database "$SHOP_POSTGRES_CONTAINER" "$SHOP_POSTGRES_USER" "$SHOP_POSTGRES_PASSWORD" \
      "$shop_restore_db" "$shop_dump" || result=$?
  fi

  drop_database "$BANK_POSTGRES_CONTAINER" "$POSTGRES_USER" "$POSTGRES_PASSWORD" "$bank_restore_db"
  drop_database "$SHOP_POSTGRES_CONTAINER" "$SHOP_POSTGRES_USER" "$SHOP_POSTGRES_PASSWORD" "$shop_restore_db"

  if [[ $result -eq 0 ]]; then
    echo "Test odtworzenia zakonczony poprawnie. Bazy tymczasowe zostaly usuniete."
  fi
  return "$result"
}

prune_old_runs() {
  local base_dir="$1" current_run="$2" candidate candidate_name resolved modified cutoff
  local removed=0
  cutoff=$(( $(date +%s) - BACKUP_RETENTION_DAYS * 86400 ))
  shopt -s nullglob
  for candidate in "$base_dir"/????????T??????Z; do
    [[ -d "$candidate" ]] || continue
    candidate_name="$(basename "$candidate")"
    [[ "$candidate_name" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || continue
    resolved="$(cd "$candidate" && pwd -P)"
    case "$resolved" in
      "$base_dir"/*) ;;
      *)
        shopt -u nullglob
        echo "Odmowa usuniecia katalogu spoza katalogu bazowego: $resolved" >&2
        return 1
        ;;
    esac
    [[ "$resolved" != "$current_run" ]] || continue
    modified="$(file_mtime_epoch "$resolved")" || return 1
    (( modified < cutoff )) || continue
    echo "Usuwanie starego kompletnego wykonania: $resolved"
    rm -rf "$resolved"
    removed=$((removed + 1))
  done
  shopt -u nullglob
  PRUNED_RUNS="$removed"
}

run_logged() {
  local report_file="$1" label="$2"
  shift 2
  printf '\n== %s ==\n' "$label" | tee -a "$report_file"
  if "$@" 2>&1 | tee -a "$report_file"; then
    return 0
  else
    local exit_code=$?
    printf 'BLAD: etap zakonczyl sie kodem %s.\n' "$exit_code" | tee -a "$report_file"
    return "$exit_code"
  fi
}

create_backup() {
  local selected_backup_dir="" skip_restore_test=false prune=false
  while (($# > 0)); do
    case "$1" in
      --backup-dir)
        [[ $# -ge 2 ]] || { echo "Brak wartosci dla --backup-dir." >&2; return 2; }
        selected_backup_dir="$2"
        shift 2
        ;;
      --skip-restore-test)
        skip_restore_test=true
        shift
        ;;
      --prune)
        prune=true
        shift
        ;;
      --help|-h)
        usage
        return 0
        ;;
      *)
        echo "Nieznany argument create: $1" >&2
        return 2
        ;;
    esac
  done

  if [[ "$BACKUP_DELETE_OLD" == "true" ]]; then
    prune=true
  fi
  if ! [[ "$BACKUP_RETENTION_DAYS" =~ ^[1-9][0-9]*$ ]]; then
    echo "BACKUP_RETENTION_DAYS musi byc dodatnia liczba." >&2
    return 1
  fi

  selected_backup_dir="$(select_backup_dir "$selected_backup_dir")" || return $?
  mkdir -p "$selected_backup_dir"
  local base_backup_dir
  base_backup_dir="$(cd "$selected_backup_dir" && pwd -P)"
  [[ -w "$base_backup_dir" ]] || { echo "Brak prawa zapisu do: $base_backup_dir" >&2; return 1; }

  local lock_dir="$base_backup_dir/.backup-all.lock"
  if ! mkdir "$lock_dir" 2>/dev/null; then
    echo "Inna operacja backupu jest juz uruchomiona: $lock_dir" >&2
    return 1
  fi
  ACTIVE_LOCK_DIR="$lock_dir"

  local run_stamp backup_dir report_file start_epoch started_at
  run_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  backup_dir="$base_backup_dir/$run_stamp"
  if ! mkdir "$backup_dir"; then
    rmdir "$lock_dir" 2>/dev/null || true
    echo "Nie mozna utworzyc katalogu wykonania: $backup_dir" >&2
    return 1
  fi
  report_file="$backup_dir/backup-report.log"
  start_epoch="$(date +%s)"
  started_at="$(date '+%Y-%m-%d %H:%M:%S %Z')"

  local preflight_status="NIE WYKONANO" backup_status="NIE WYKONANO"
  local checksum_status="NIE WYKONANO" restore_status="POMINIETY"
  local retention_status="POMINIETA" bank_dump="" shop_dump="" result=0
  PRUNED_RUNS=0

  {
    echo "NovaBank - automatyczna kopia PostgreSQL"
    echo "Katalog bazowy: $base_backup_dir"
    echo "Miejsce zapisania tej kopii: $backup_dir"
    echo "Plik srodowiskowy: $ENV_FILE"
    echo "Kontenery: $BANK_POSTGRES_CONTAINER, $SHOP_POSTGRES_CONTAINER"
  } | tee -a "$report_file"

  for command_name in bash docker stat date du; do
    if ! require_command "$command_name" >> "$report_file" 2>&1; then
      result=1
      break
    fi
  done
  if [[ $result -eq 0 ]] && ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
    echo "Brak wymaganej komendy sha256sum lub shasum." | tee -a "$report_file"
    result=1
  fi
  if [[ $result -eq 0 ]] && ! validate_database_configuration >> "$report_file" 2>&1; then
    result=1
  fi
  if [[ $result -eq 0 ]]; then
    local container running
    for container in "$BANK_POSTGRES_CONTAINER" "$SHOP_POSTGRES_CONTAINER"; do
      running="$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || true)"
      if [[ "$running" != "true" ]]; then
        echo "Kontener nie jest uruchomiony: $container" | tee -a "$report_file"
        result=1
      fi
    done
  fi
  [[ $result -ne 0 ]] || preflight_status="OK"

  if [[ $result -eq 0 ]]; then
    if run_logged "$report_file" "Tworzenie kopii banku i sklepu" \
      create_dump_pair "$backup_dir" "$run_stamp"; then
      backup_status="OK"
    else
      backup_status="BLAD"
      result=1
    fi
  fi

  if [[ $result -eq 0 ]]; then
    bank_dump="$(latest_dump_in_dir bank "$backup_dir")"
    shop_dump="$(latest_dump_in_dir shop "$backup_dir")"
    if [[ -z "$bank_dump" || ! -f "$bank_dump" || -z "$shop_dump" || ! -f "$shop_dump" ]]; then
      echo "Nie znaleziono nowo utworzonych kopii." | tee -a "$report_file"
      result=1
    fi
  fi

  if [[ $result -eq 0 ]]; then
    if run_logged "$report_file" "Weryfikacja sum SHA-256" \
      checksum_verify "${bank_dump}.sha256" "${shop_dump}.sha256"; then
      checksum_status="OK"
    else
      checksum_status="BLAD"
      result=1
    fi
  fi

  if [[ $result -eq 0 && "$skip_restore_test" == "false" ]]; then
    if run_logged "$report_file" "Testowe odtworzenie obu baz" \
      restore_test_databases "$bank_dump" "$shop_dump"; then
      restore_status="OK"
    else
      restore_status="BLAD"
      result=1
    fi
  fi

  if [[ $result -eq 0 && "$prune" == "true" ]]; then
    printf '\n== Retencja starych katalogow kopii ==\n' | tee -a "$report_file"
    if prune_old_runs "$base_backup_dir" "$backup_dir" > >(tee -a "$report_file") 2>&1; then
      retention_status="WYKONANA"
    else
      retention_status="BLAD"
      result=1
    fi
  fi

  local end_epoch duration final_result bank_size="brak" shop_size="brak"
  end_epoch="$(date +%s)"
  duration=$((end_epoch - start_epoch))
  final_result="SUKCES"
  [[ $result -eq 0 ]] || final_result="BLAD"
  [[ -n "$bank_dump" && -f "$bank_dump" ]] && bank_size="$(du -h "$bank_dump" | cut -f1)"
  [[ -n "$shop_dump" && -f "$shop_dump" ]] && shop_size="$(du -h "$shop_dump" | cut -f1)"

  {
    printf '\n================================================\n'
    printf 'NovaBank - podsumowanie kopii zapasowej\n'
    printf '================================================\n'
    printf 'Rozpoczecie:             %s\n' "$started_at"
    printf 'Katalog bazowy:          %s\n' "$base_backup_dir"
    printf 'Miejsce zapisania kopii: %s\n' "$backup_dir"
    printf 'Kontrola srodowiska:     %s\n' "$preflight_status"
    printf 'Backup PostgreSQL:       %s\n' "$backup_status"
    printf 'Sumy SHA-256:            %s\n' "$checksum_status"
    printf 'Test odtwarzania:        %s\n' "$restore_status"
    printf 'Retencja starych kopii:  %s (usunieto: %s)\n' "$retention_status" "$PRUNED_RUNS"
    printf 'Kopia banku:             %s (%s)\n' "${bank_dump:-brak}" "$bank_size"
    printf 'Kopia sklepu:            %s (%s)\n' "${shop_dump:-brak}" "$shop_size"
    printf 'Czas wykonania:          %s s\n' "$duration"
    printf 'Raport:                  %s\n' "$report_file"
    printf 'Wynik koncowy:           %s\n' "$final_result"
    printf '================================================\n'
  } | tee -a "$report_file"

  rmdir "$lock_dir" 2>/dev/null || true
  ACTIVE_LOCK_DIR=""
  return "$result"
}

create_dump_pair() {
  local output_dir="$1" stamp="$2"
  dump_database bank "$BANK_POSTGRES_CONTAINER" "$POSTGRES_USER" "$POSTGRES_DB" \
    "$POSTGRES_PASSWORD" "$output_dir" "$stamp" || return $?
  dump_database shop "$SHOP_POSTGRES_CONTAINER" "$SHOP_POSTGRES_USER" "$SHOP_POSTGRES_DB" \
    "$SHOP_POSTGRES_PASSWORD" "$output_dir" "$stamp"
}

restore_test_command() {
  local selected_backup_dir="" bank_dump="$CALLER_BANK_DUMP" shop_dump="$CALLER_SHOP_DUMP"
  while (($# > 0)); do
    case "$1" in
      --backup-dir)
        [[ $# -ge 2 ]] || { echo "Brak wartosci dla --backup-dir." >&2; return 2; }
        selected_backup_dir="$2"
        shift 2
        ;;
      --help|-h)
        usage
        return 0
        ;;
      *)
        echo "Nieznany argument restore-test: $1" >&2
        return 2
        ;;
    esac
  done

  if [[ -n "$bank_dump" && -z "$shop_dump" ]] || [[ -z "$bank_dump" && -n "$shop_dump" ]]; then
    echo "Podaj jednoczesnie BANK_DUMP i SHOP_DUMP albo nie podawaj zadnego." >&2
    return 1
  fi

  if [[ -z "$bank_dump" ]]; then
    selected_backup_dir="${selected_backup_dir:-${CONFIGURED_BACKUP_DIR:-$DEFAULT_BACKUP_DIR}}"
    [[ "$selected_backup_dir" == /* ]] || {
      echo "Katalog backupu musi byc sciezka absolutna." >&2
      return 2
    }
    local latest_run_dir
    latest_run_dir="$(latest_complete_run "$selected_backup_dir" || true)"
    if [[ -n "$latest_run_dir" ]]; then
      bank_dump="$(latest_dump_in_dir bank "$latest_run_dir")"
      shop_dump="$(latest_dump_in_dir shop "$latest_run_dir")"
      echo "Wybrano najnowsza kompletna kopie: $latest_run_dir"
    else
      bank_dump="$(latest_dump_in_dir bank "$selected_backup_dir")"
      shop_dump="$(latest_dump_in_dir shop "$selected_backup_dir")"
    fi
  fi

  [[ -n "$bank_dump" && -f "$bank_dump" ]] || { echo "Nie znaleziono backupu banku." >&2; return 1; }
  [[ -n "$shop_dump" && -f "$shop_dump" ]] || { echo "Nie znaleziono backupu sklepu." >&2; return 1; }
  [[ -f "${bank_dump}.sha256" ]] || { echo "Brak sumy SHA-256: ${bank_dump}.sha256" >&2; return 1; }
  [[ -f "${shop_dump}.sha256" ]] || { echo "Brak sumy SHA-256: ${shop_dump}.sha256" >&2; return 1; }

  require_command docker
  validate_database_configuration
  checksum_verify "${bank_dump}.sha256" "${shop_dump}.sha256"
  restore_test_databases "$bank_dump" "$shop_dump"
}

dr_wait_for_http() {
  local url="$1" attempts="${2:-90}" attempt
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if curl --silent --show-error --fail --max-time 5 "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "Przekroczono czas oczekiwania na: $url" >&2
  return 1
}

dr_wait_for_postgres() {
  local container="$1" user="$2" database="$3" password="$4" attempt
  for ((attempt = 1; attempt <= 60; attempt++)); do
    if docker exec -e PGPASSWORD="$password" "$container" \
      pg_isready -U "$user" -d "$database" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "Izolowany PostgreSQL nie osiagnal gotowosci: $container" >&2
  return 1
}

dr_container_port() {
  local container="$1" port="$2" mapping
  mapping="$(docker port "$container" "$port/tcp" | head -n 1)"
  [[ -n "$mapping" ]] || {
    echo "Nie znaleziono portu $port kontenera $container." >&2
    return 1
  }
  printf '%s\n' "${mapping##*:}"
}

dr_start_container() {
  local name="$1"
  shift
  docker run -d --name "$name" --label "novabank.dr-test=true" "$@" >/dev/null || return $?
  DR_CONTAINERS+=("$name")
}

dr_restore_dump() {
  local container="$1" user="$2" database="$3" password="$4" dump="$5"
  echo "Odtwarzanie $dump w izolowanym kontenerze $container..."
  docker exec -i -e PGPASSWORD="$password" "$container" pg_restore \
    --exit-on-error --clean --if-exists --no-owner --no-privileges \
    -U "$user" -d "$database" < "$dump"
}

dr_build_images() {
  echo "Budowanie obrazu aplikacji bankowej..."
  docker build --pull=false \
    -f "$REPO_DIR/infra/disaster-recovery/Dockerfile.bank" \
    -t novabank-dr-bank:local "$REPO_DIR" || return $?
  echo "Budowanie obrazu aplikacji sklepowej..."
  docker build --pull=false \
    -f "$REPO_DIR/infra/disaster-recovery/Dockerfile.shop" \
    -t novabank-dr-shop:local "$REPO_DIR"
}

dr_collect_application_logs() {
  local bank_app="$1" shop_app="$2" report_dir="$3"
  docker logs "$bank_app" > "$report_dir/bank-application.log" 2>&1 || true
  docker logs "$shop_app" > "$report_dir/shop-application.log" 2>&1 || true
}

dr_execute() {
  local bank_dump="$1" shop_dump="$2" report_dir="$3" run_id="$4"
  local suffix
  suffix="$(printf '%s' "$run_id" | tr '[:upper:]' '[:lower:]')"
  local network="novabank-dr-${suffix}"
  local bank_db="novabank-dr-bank-db-${suffix}"
  local shop_db="novabank-dr-shop-db-${suffix}"
  local redis="novabank-dr-redis-${suffix}"
  local bank_app="novabank-dr-bank-app-${suffix}"
  local shop_app="novabank-dr-shop-app-${suffix}"
  local bank_user="bank_dr" shop_user="shop_dr"
  local bank_password="dr-bank-${run_id}" shop_password="dr-shop-${run_id}"
  local bank_database="bankdb" shop_database="shopdb"
  local api_key="dr-api-${run_id}"
  local merchant_account="${SHOP_MERCHANT_ACCOUNT_NUMBER:-}"
  local bank_port shop_port bank_management_port shop_management_port
  local bank_url shop_url bank_health_url shop_health_url

  [[ "$merchant_account" =~ ^[0-9]{26}$ ]] || {
    echo "SHOP_MERCHANT_ACCOUNT_NUMBER musi zawierac 26 cyfr, aby wykonac test HTTP platnosci." >&2
    echo "Ustaw zmienna w pliku $ENV_FILE albo przekaz ENV_FILE=/sciezka/do/pliku." >&2
    echo "Przyklad: SHOP_MERCHANT_ACCOUNT_NUMBER=12345678901234567890123456" >&2
    return 1
  }

  DR_NETWORK="$network"
  DR_LOG_DIR="$report_dir/container-logs"
  mkdir -p "$DR_LOG_DIR"
  docker network create --label "novabank.dr-test=true" "$network" >/dev/null || return $?

  echo "Uruchamianie izolowanych baz i Redis w sieci $network..."
  dr_start_container "$bank_db" --network "$network" \
    -e POSTGRES_DB="$bank_database" -e POSTGRES_USER="$bank_user" \
    -e POSTGRES_PASSWORD="$bank_password" postgres:18 || return $?
  dr_start_container "$shop_db" --network "$network" \
    -e POSTGRES_DB="$shop_database" -e POSTGRES_USER="$shop_user" \
    -e POSTGRES_PASSWORD="$shop_password" postgres:18 || return $?
  dr_start_container "$redis" --network "$network" redis:7.4-alpine || return $?

  dr_wait_for_postgres "$bank_db" "$bank_user" "$bank_database" "$bank_password" || return $?
  dr_wait_for_postgres "$shop_db" "$shop_user" "$shop_database" "$shop_password" || return $?
  dr_restore_dump "$bank_db" "$bank_user" "$bank_database" "$bank_password" "$bank_dump" || return $?
  dr_restore_dump "$shop_db" "$shop_user" "$shop_database" "$shop_password" "$shop_dump" || return $?

  dr_build_images || return $?

  echo "Uruchamianie aplikacji; porty sa publikowane wylacznie na 127.0.0.1 i wybierane losowo..."
  dr_start_container "$bank_app" --network "$network" \
    -p 127.0.0.1::8080 -p 127.0.0.1::8082 \
    -e TEST_POSTGRES_URL="jdbc:postgresql://${bank_db}:5432/${bank_database}" \
    -e TEST_POSTGRES_USER="$bank_user" -e TEST_POSTGRES_PASSWORD="$bank_password" \
    -e TEST_REDIS_HOST="$redis" -e TEST_REDIS_PORT=6379 \
    -e TEST_BANK_API_KEY="$api_key" \
    -e TEST_TOTP_ENCRYPTION_KEY="MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=" \
    -e BANK_API_URL="http://${bank_app}:8080" \
    -e SHOP_PAYMENT_CALLBACK_URL="http://${shop_app}:8081/payment/callback" \
    novabank-dr-bank:local \
    --spring.profiles.active=test --server.port=8080 \
    --management.server.port=8082 --management.server.address=0.0.0.0 || return $?

  dr_start_container "$shop_app" --network "$network" \
    -p 127.0.0.1::8081 -p 127.0.0.1::8083 \
    -e TEST_SHOP_POSTGRES_URL="jdbc:postgresql://${shop_db}:5432/${shop_database}" \
    -e TEST_SHOP_POSTGRES_USER="$shop_user" -e TEST_SHOP_POSTGRES_PASSWORD="$shop_password" \
    -e TEST_REDIS_HOST="$redis" -e TEST_REDIS_PORT=6379 \
    -e REDIS_HOST="$redis" -e REDIS_PORT=6379 \
    -e TEST_BANK_BASE_URL="http://${bank_app}:8080" \
    -e TEST_BANK_API_KEY="$api_key" -e TEST_BANK_API_KEY_ID=legacy \
    -e TEST_SHOP_MERCHANT_ACCOUNT="$merchant_account" \
    novabank-dr-shop:local \
    --spring.profiles.active=test --server.port=8081 \
    --management.server.port=8083 --management.server.address=0.0.0.0 || return $?

  bank_port="$(dr_container_port "$bank_app" 8080)" || return $?
  shop_port="$(dr_container_port "$shop_app" 8081)" || return $?
  bank_management_port="$(dr_container_port "$bank_app" 8082)" || return $?
  shop_management_port="$(dr_container_port "$shop_app" 8083)" || return $?
  bank_url="http://127.0.0.1:${bank_port}"
  shop_url="http://127.0.0.1:${shop_port}"
  bank_health_url="http://127.0.0.1:${bank_management_port}/actuator/health/readiness"
  shop_health_url="http://127.0.0.1:${shop_management_port}/actuator/health/readiness"

  echo "Oczekiwanie na migracje Flyway i gotowosc HTTP..."
  if ! dr_wait_for_http "$bank_health_url"; then
    dr_collect_application_logs "$bank_app" "$shop_app" "$report_dir"
    return 1
  fi
  if ! dr_wait_for_http "$shop_health_url"; then
    dr_collect_application_logs "$bank_app" "$shop_app" "$report_dir"
    return 1
  fi

  echo "Stan migracji po uruchomieniu bieżącej wersji aplikacji:"
  docker exec -e PGPASSWORD="$bank_password" "$bank_db" psql \
    -U "$bank_user" -d "$bank_database" -v ON_ERROR_STOP=1 \
    -c "SELECT count(*) AS bank_migrations, max(installed_rank) AS latest_rank FROM flyway_schema_history WHERE success;" || return $?
  docker exec -e PGPASSWORD="$shop_password" "$shop_db" psql \
    -U "$shop_user" -d "$shop_database" -v ON_ERROR_STOP=1 \
    -c "SELECT count(*) AS shop_migrations, max(installed_rank) AS latest_rank FROM flyway_schema_history WHERE success;" || return $?
  printf 'OK\n' > "$report_dir/migrations.status"

  echo "Uruchamianie pelnego testu HTTP bank + sklep..."
  BANK_URL="$bank_url" SHOP_URL="$shop_url" \
    BANK_HEALTH_URL="$bank_health_url" SHOP_HEALTH_URL="$shop_health_url" \
    BANK_API_KEY="$api_key" BANK_API_KEY_ID=legacy \
    bash "$REPO_DIR/scripts/e2e-smoke.sh" || {
      dr_collect_application_logs "$bank_app" "$shop_app" "$report_dir"
      return 1
    }

  dr_collect_application_logs "$bank_app" "$shop_app" "$report_dir"
  printf 'OK\n' > "$report_dir/http-tests.status"
  echo "Test HTTP zakonczony poprawnie. Izolowane dane zostana usuniete."
}

disaster_recovery_command() {
  local selected_backup_dir=""
  while (($# > 0)); do
    case "$1" in
      --backup-dir)
        [[ $# -ge 2 ]] || { echo "Brak wartosci dla --backup-dir." >&2; return 2; }
        selected_backup_dir="$2"
        shift 2
        ;;
      --help|-h)
        usage
        return 0
        ;;
      *)
        echo "Nieznany argument dr-test: $1" >&2
        return 2
        ;;
    esac
  done

  selected_backup_dir="${selected_backup_dir:-${CONFIGURED_BACKUP_DIR:-$DEFAULT_BACKUP_DIR}}"
  [[ "$selected_backup_dir" == /* ]] || {
    echo "Katalog backupu musi byc sciezka absolutna." >&2
    return 2
  }
  local latest_run_dir bank_dump shop_dump run_stamp report_dir report_file result started_at end_epoch start_epoch
  latest_run_dir="$(latest_complete_run "$selected_backup_dir" || true)"
  [[ -n "$latest_run_dir" ]] || {
    echo "Nie znaleziono kompletnej kopii w $selected_backup_dir." >&2
    return 1
  }
  bank_dump="$(latest_dump_in_dir bank "$latest_run_dir")"
  shop_dump="$(latest_dump_in_dir shop "$latest_run_dir")"

  for command_name in bash docker curl jq date; do
    require_command "$command_name" || return $?
  done
  checksum_verify "${bank_dump}.sha256" "${shop_dump}.sha256" || return $?

  run_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  report_dir="$selected_backup_dir/disaster-recovery/$run_stamp"
  mkdir -p "$report_dir"
  report_file="$report_dir/dr-report.log"
  started_at="$(date '+%Y-%m-%d %H:%M:%S %Z')"
  start_epoch="$(date +%s)"

  {
    echo "NovaBank - cykliczny test awaryjnego odtworzenia"
    echo "Rozpoczecie: $started_at"
    echo "Zrodlo kopii: $latest_run_dir"
    echo "Raport: $report_file"
    echo "Plik konfiguracji: $ENV_FILE"
    echo "Rachunek sklepu: $([[ "${SHOP_MERCHANT_ACCOUNT_NUMBER:-}" =~ ^[0-9]{26}$ ]] && echo ustawiony || echo brak/niepoprawny)"
    echo "Srodowisko: izolowana siec, nowe PostgreSQL i Redis, losowe porty loopback"
  } | tee "$report_file"

  set +e
  dr_execute "$bank_dump" "$shop_dump" "$report_dir" "$run_stamp" \
    > >(tee -a "$report_file") 2>&1
  result=$?
  set -e
  cleanup_disaster_environment
  end_epoch="$(date +%s)"

  {
    echo ""
    echo "================================================"
    echo "NovaBank - wynik testu awaryjnego"
    echo "================================================"
    echo "Zrodlo kopii:         $latest_run_dir"
    echo "Raport i logi:        $report_dir"
    echo "Migracje Flyway:      $([[ -f "$report_dir/migrations.status" ]] && echo OK || echo BLAD)"
    echo "Testy HTTP:           $([[ -f "$report_dir/http-tests.status" ]] && echo OK || echo BLAD)"
    echo "Izolacja i sprzatanie: WYKONANE"
    echo "Czas wykonania:       $((end_epoch - start_epoch)) s"
    echo "Wynik koncowy:        $([[ $result -eq 0 ]] && echo SUKCES || echo BLAD)"
    echo "================================================"
  } | tee -a "$report_file"
  return "$result"
}

shell_quote() {
  printf '%q' "$1"
}

launch_unix() {
  local script_arg window_command
  script_arg="$(shell_quote "$SCRIPT_DIR/backup.sh")"
  window_command="bash $script_arg create; result=\$?; printf '\nProcedura zakonczona z kodem %s. Przeczytaj raport, a nastepnie wpisz exit.\n' \"\$result\"; exec bash -i"

  case "$(uname -s)" in
    Darwin)
      if command -v osascript >/dev/null 2>&1; then
        osascript - "$SCRIPT_DIR/backup.sh" <<'APPLESCRIPT'
on run argv
  set scriptPath to item 1 of argv
  set shellCommand to "bash " & quoted form of scriptPath & " create; result=$?; printf '\\nProcedura zakonczona z kodem %s. Przeczytaj raport, a nastepnie wpisz exit.\\n' \"$result\"; exec bash -i"
  tell application "Terminal"
    activate
    do script shellCommand
  end tell
end run
APPLESCRIPT
        echo "Uruchomiono procedure w nowym oknie Terminala."
      else
        create_backup
      fi
      ;;
    Linux)
      if [[ -z "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]]; then
        create_backup
      elif command -v gnome-terminal >/dev/null 2>&1; then
        gnome-terminal -- bash -lc "$window_command"
      elif command -v konsole >/dev/null 2>&1; then
        konsole -e bash -lc "$window_command"
      elif command -v x-terminal-emulator >/dev/null 2>&1; then
        x-terminal-emulator -e bash -lc "$window_command"
      elif command -v xterm >/dev/null 2>&1; then
        xterm -e bash -lc "$window_command" &
      else
        create_backup
      fi
      ;;
    *)
      echo "Nieobslugiwany system: $(uname -s)." >&2
      return 1
      ;;
  esac
}

validate_daily_time() {
  [[ "$1" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]] || {
    echo "Godzina musi miec format HH:MM, np. 02:00 lub 23:30." >&2
    return 2
  }
}

validate_generated_path() {
  local value="$1"
  [[ "$value" == /* ]] || {
    echo "Sciezka harmonogramu musi byc absolutna: $value" >&2
    return 2
  }
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || {
    echo "Sciezka nie moze zawierac znaku nowej linii." >&2
    return 2
  }
}

systemd_quote() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//%/%%}"
  printf '"%s"' "$value"
}

systemd_path_escape() {
  local value="$1"
  value="${value//%/%%}"
  value="${value// /\\x20}"
  value="${value//$'\t'/\\x09}"
  printf '%s' "$value"
}

xml_escape() {
  printf '%s' "$1" | sed \
    -e 's/&/\&amp;/g' \
    -e 's/</\&lt;/g' \
    -e 's/>/\&gt;/g'
}

schedule_platform() {
  case "$(uname -s)" in
    Linux) printf '%s\n' systemd ;;
    Darwin) printf '%s\n' launchd ;;
    *)
      echo "Harmonogram nie obsluguje systemu: $(uname -s)." >&2
      return 1
      ;;
  esac
}

render_systemd_service() {
  local backup_dir="$1" bash_path
  bash_path="$(command -v bash)"
  cat <<EOF
[Unit]
Description=NovaBank automatic PostgreSQL backup
After=network-online.target

[Service]
Type=oneshot
WorkingDirectory=$(systemd_path_escape "$REPO_DIR")
ExecStart=$(systemd_quote "$bash_path") $(systemd_quote "$SCRIPT_DIR/backup.sh") prune --backup-dir $(systemd_quote "$backup_dir")
UMask=0077
TimeoutStartSec=30min
EOF
}

render_systemd_timer() {
  local daily_at="$1"
  cat <<EOF
[Unit]
Description=Daily NovaBank PostgreSQL backup

[Timer]
OnCalendar=*-*-* ${daily_at}:00
Persistent=true
AccuracySec=1min
Unit=${SYSTEMD_UNIT_NAME}.service

[Install]
WantedBy=timers.target
EOF
}

render_launchd_plist() {
  local backup_dir="$1" daily_at="$2" hour minute bash_path log_dir
  hour="${daily_at%%:*}"
  minute="${daily_at##*:}"
  hour=$((10#$hour))
  minute=$((10#$minute))
  bash_path="$(command -v bash)"
  log_dir="$HOME/Library/Logs"
  cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${LAUNCHD_LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>$(xml_escape "$bash_path")</string>
    <string>$(xml_escape "$SCRIPT_DIR/backup.sh")</string>
    <string>prune</string>
    <string>--backup-dir</string>
    <string>$(xml_escape "$backup_dir")</string>
  </array>
  <key>WorkingDirectory</key>
  <string>$(xml_escape "$REPO_DIR")</string>
  <key>StartCalendarInterval</key>
  <dict>
    <key>Hour</key>
    <integer>${hour}</integer>
    <key>Minute</key>
    <integer>${minute}</integer>
  </dict>
  <key>StandardOutPath</key>
  <string>$(xml_escape "$log_dir/novabank-backup.log")</string>
  <key>StandardErrorPath</key>
  <string>$(xml_escape "$log_dir/novabank-backup-error.log")</string>
  <key>Umask</key>
  <integer>63</integer>
</dict>
</plist>
EOF
}

validate_weekday() {
  local normalized
  normalized="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"
  case "$normalized" in
    MON|TUE|WED|THU|FRI|SAT|SUN) ;;
    *)
      echo "Dzien tygodnia musi miec wartosc MON, TUE, WED, THU, FRI, SAT lub SUN." >&2
      return 2
      ;;
  esac
}

launchd_weekday_number() {
  local normalized
  normalized="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"
  case "$normalized" in
    SUN) echo 1 ;;
    MON) echo 2 ;;
    TUE) echo 3 ;;
    WED) echo 4 ;;
    THU) echo 5 ;;
    FRI) echo 6 ;;
    SAT) echo 7 ;;
  esac
}

systemd_weekday_name() {
  local normalized
  normalized="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"
  case "$normalized" in
    MON) echo Mon ;;
    TUE) echo Tue ;;
    WED) echo Wed ;;
    THU) echo Thu ;;
    FRI) echo Fri ;;
    SAT) echo Sat ;;
    SUN) echo Sun ;;
  esac
}

render_dr_systemd_service() {
  local backup_dir="$1" bash_path
  bash_path="$(command -v bash)"
  cat <<EOF
[Unit]
Description=NovaBank isolated disaster recovery test
After=network-online.target docker.service

[Service]
Type=oneshot
WorkingDirectory=$(systemd_path_escape "$REPO_DIR")
ExecStart=$(systemd_quote "$bash_path") $(systemd_quote "$SCRIPT_DIR/backup.sh") dr-test --backup-dir $(systemd_quote "$backup_dir")
UMask=0077
TimeoutStartSec=90min
EOF
}

render_dr_systemd_timer() {
  local weekday="$1" at="$2" systemd_day
  systemd_day="$(systemd_weekday_name "$weekday")"
  cat <<EOF
[Unit]
Description=Weekly NovaBank disaster recovery test

[Timer]
OnCalendar=${systemd_day} *-*-* ${at}:00
Persistent=true
AccuracySec=5min
Unit=${DR_SYSTEMD_UNIT_NAME}.service

[Install]
WantedBy=timers.target
EOF
}

render_dr_launchd_plist() {
  local backup_dir="$1" weekday="$2" at="$3" hour minute weekday_number bash_path log_dir
  hour="${at%%:*}"
  minute="${at##*:}"
  hour=$((10#$hour))
  minute=$((10#$minute))
  weekday_number="$(launchd_weekday_number "$weekday")"
  bash_path="$(command -v bash)"
  log_dir="$HOME/Library/Logs"
  cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${DR_LAUNCHD_LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>$(xml_escape "$bash_path")</string>
    <string>$(xml_escape "$SCRIPT_DIR/backup.sh")</string>
    <string>dr-test</string>
    <string>--backup-dir</string>
    <string>$(xml_escape "$backup_dir")</string>
  </array>
  <key>WorkingDirectory</key>
  <string>$(xml_escape "$REPO_DIR")</string>
  <key>StartCalendarInterval</key>
  <dict>
    <key>Weekday</key>
    <integer>${weekday_number}</integer>
    <key>Hour</key>
    <integer>${hour}</integer>
    <key>Minute</key>
    <integer>${minute}</integer>
  </dict>
  <key>StandardOutPath</key>
  <string>$(xml_escape "$log_dir/novabank-dr-test.log")</string>
  <key>StandardErrorPath</key>
  <string>$(xml_escape "$log_dir/novabank-dr-test-error.log")</string>
  <key>Umask</key>
  <integer>63</integer>
</dict>
</plist>
EOF
}

dr_schedule_install() {
  local weekday="SUN" at="04:00" selected_backup_dir="" dry_run=false platform
  while (($# > 0)); do
    case "$1" in
      --weekday)
        [[ $# -ge 2 ]] || { echo "Brak wartosci dla --weekday." >&2; return 2; }
        weekday="$(printf '%s' "$2" | tr '[:lower:]' '[:upper:]')"
        shift 2
        ;;
      --at)
        [[ $# -ge 2 ]] || { echo "Brak wartosci dla --at." >&2; return 2; }
        at="$2"
        shift 2
        ;;
      --backup-dir)
        [[ $# -ge 2 ]] || { echo "Brak wartosci dla --backup-dir." >&2; return 2; }
        selected_backup_dir="$2"
        shift 2
        ;;
      --dry-run)
        dry_run=true
        shift
        ;;
      --help|-h)
        usage
        return 0
        ;;
      *)
        echo "Nieznany argument dr-schedule-install: $1" >&2
        return 2
        ;;
    esac
  done

  validate_weekday "$weekday" || return $?
  validate_daily_time "$at" || return $?
  selected_backup_dir="$(select_backup_dir "$selected_backup_dir")" || return $?
  validate_generated_path "$selected_backup_dir" || return $?
  validate_generated_path "$REPO_DIR" || return $?
  platform="$(schedule_platform)" || return $?

  if [[ "$dry_run" == "false" ]]; then
    mkdir -p "$selected_backup_dir"
    [[ -w "$selected_backup_dir" ]] || {
      echo "Brak prawa zapisu do katalogu kopii: $selected_backup_dir" >&2
      return 1
    }
  fi

  if [[ "$platform" == "systemd" ]]; then
    local unit_dir="$HOME/.config/systemd/user"
    local service_file="$unit_dir/${DR_SYSTEMD_UNIT_NAME}.service"
    local timer_file="$unit_dir/${DR_SYSTEMD_UNIT_NAME}.timer"
    if [[ "$dry_run" == "true" ]]; then
      echo "--- $service_file ---"
      render_dr_systemd_service "$selected_backup_dir"
      echo "--- $timer_file ---"
      render_dr_systemd_timer "$weekday" "$at"
      return 0
    fi
    require_command systemctl || return $?
    mkdir -p "$unit_dir"
    render_dr_systemd_service "$selected_backup_dir" > "$service_file"
    render_dr_systemd_timer "$weekday" "$at" > "$timer_file"
    chmod 600 "$service_file" "$timer_file"
    systemctl --user daemon-reload || {
      echo "Nie mozna polaczyc sie z menedzerem systemd uzytkownika." >&2
      return 1
    }
    if ! systemctl --user enable --now "${DR_SYSTEMD_UNIT_NAME}.timer"; then
      echo "Nie udalo sie uruchomic timera ${DR_SYSTEMD_UNIT_NAME}.timer." >&2
      systemctl --user status "${DR_SYSTEMD_UNIT_NAME}.timer" --no-pager >&2 || true
      return 1
    fi
    echo "Zainstalowano cotygodniowy test awaryjny: $weekday o $at."
    echo "Katalog kopii: $selected_backup_dir"
    systemctl --user list-timers "${DR_SYSTEMD_UNIT_NAME}.timer" --no-pager
    if command -v loginctl >/dev/null 2>&1; then
      local linger_status
      linger_status="$(loginctl show-user "$USER" -p Linger --value 2>/dev/null || true)"
      if [[ "$linger_status" != "yes" ]]; then
        echo ""
        echo "UWAGA: linger dla uzytkownika $USER nie jest wlaczony."
        echo "Dla pracy bez otwartego terminala wykonaj jednorazowo:"
        echo "  sudo loginctl enable-linger $USER"
      fi
    fi
  else
    local agents_dir="$HOME/Library/LaunchAgents"
    local logs_dir="$HOME/Library/Logs"
    local plist_file="$agents_dir/${DR_LAUNCHD_LABEL}.plist"
    if [[ "$dry_run" == "true" ]]; then
      echo "--- $plist_file ---"
      render_dr_launchd_plist "$selected_backup_dir" "$weekday" "$at"
      return 0
    fi
    require_command launchctl || return $?
    mkdir -p "$agents_dir" "$logs_dir"
    launchctl bootout "gui/$UID" "$plist_file" >/dev/null 2>&1 || true
    render_dr_launchd_plist "$selected_backup_dir" "$weekday" "$at" > "$plist_file"
    chmod 600 "$plist_file"
    launchctl bootstrap "gui/$UID" "$plist_file"
    launchctl enable "gui/$UID/$DR_LAUNCHD_LABEL"
    echo "Zainstalowano cotygodniowy test awaryjny: $weekday o $at."
    echo "Katalog kopii: $selected_backup_dir"
  fi
}

dr_schedule_status() {
  local platform
  platform="$(schedule_platform)" || return $?
  if [[ "$platform" == "systemd" ]]; then
    systemctl --user status "${DR_SYSTEMD_UNIT_NAME}.timer" --no-pager
    echo ""
    systemctl --user list-timers "${DR_SYSTEMD_UNIT_NAME}.timer" --no-pager
  else
    launchctl print "gui/$UID/$DR_LAUNCHD_LABEL"
  fi
}

dr_schedule_run_now() {
  local platform
  platform="$(schedule_platform)" || return $?
  if [[ "$platform" == "systemd" ]]; then
    if ! systemctl --user start "${DR_SYSTEMD_UNIT_NAME}.service"; then
      journalctl --user -u "${DR_SYSTEMD_UNIT_NAME}.service" -n 100 --no-pager >&2 || true
      return 1
    fi
    systemctl --user show "${DR_SYSTEMD_UNIT_NAME}.service" \
      -p Result -p ExecMainStatus -p ExecMainStartTimestamp -p ExecMainExitTimestamp --no-pager
  else
    launchctl kickstart -k "gui/$UID/$DR_LAUNCHD_LABEL"
    echo "Uruchomiono zadanie $DR_LAUNCHD_LABEL."
  fi
}

dr_schedule_logs() {
  local platform
  platform="$(schedule_platform)" || return $?
  if [[ "$platform" == "systemd" ]]; then
    journalctl --user -u "${DR_SYSTEMD_UNIT_NAME}.service" -n 200 --no-pager
  else
    echo "--- standard output ---"
    tail -n 200 "$HOME/Library/Logs/novabank-dr-test.log" 2>/dev/null || echo "Brak logu standardowego."
    echo "--- errors ---"
    tail -n 200 "$HOME/Library/Logs/novabank-dr-test-error.log" 2>/dev/null || echo "Brak logu bledow."
  fi
}

dr_schedule_remove() {
  local platform
  platform="$(schedule_platform)" || return $?
  if [[ "$platform" == "systemd" ]]; then
    local unit_dir="$HOME/.config/systemd/user"
    systemctl --user disable --now "${DR_SYSTEMD_UNIT_NAME}.timer" >/dev/null 2>&1 || true
    systemctl --user stop "${DR_SYSTEMD_UNIT_NAME}.service" >/dev/null 2>&1 || true
    rm -f "$unit_dir/${DR_SYSTEMD_UNIT_NAME}.timer" "$unit_dir/${DR_SYSTEMD_UNIT_NAME}.service"
    systemctl --user daemon-reload
    systemctl --user reset-failed >/dev/null 2>&1 || true
    echo "Usunieto harmonogram testu awaryjnego NovaBank."
  else
    local plist_file="$HOME/Library/LaunchAgents/${DR_LAUNCHD_LABEL}.plist"
    launchctl bootout "gui/$UID" "$plist_file" >/dev/null 2>&1 || true
    rm -f "$plist_file"
    echo "Usunieto harmonogram testu awaryjnego NovaBank."
  fi
}

schedule_install() {
  local daily_at="02:00" selected_backup_dir="" dry_run=false platform
  while (($# > 0)); do
    case "$1" in
      --daily-at)
        [[ $# -ge 2 ]] || { echo "Brak wartosci dla --daily-at." >&2; return 2; }
        daily_at="$2"
        shift 2
        ;;
      --backup-dir)
        [[ $# -ge 2 ]] || { echo "Brak wartosci dla --backup-dir." >&2; return 2; }
        selected_backup_dir="$2"
        shift 2
        ;;
      --dry-run)
        dry_run=true
        shift
        ;;
      --help|-h)
        usage
        return 0
        ;;
      *)
        echo "Nieznany argument schedule-install: $1" >&2
        return 2
        ;;
    esac
  done

  validate_daily_time "$daily_at" || return $?
  selected_backup_dir="$(select_backup_dir "$selected_backup_dir")" || return $?
  validate_generated_path "$selected_backup_dir" || return $?
  validate_generated_path "$REPO_DIR" || return $?
  platform="$(schedule_platform)" || return $?

  if [[ "$dry_run" == "false" ]]; then
    mkdir -p "$selected_backup_dir"
    [[ -w "$selected_backup_dir" ]] || {
      echo "Brak prawa zapisu do katalogu kopii: $selected_backup_dir" >&2
      return 1
    }
  fi

  if [[ "$platform" == "systemd" ]]; then
    local unit_dir="$HOME/.config/systemd/user"
    local service_file="$unit_dir/${SYSTEMD_UNIT_NAME}.service"
    local timer_file="$unit_dir/${SYSTEMD_UNIT_NAME}.timer"
    if [[ "$dry_run" == "true" ]]; then
      echo "--- $service_file ---"
      render_systemd_service "$selected_backup_dir"
      echo "--- $timer_file ---"
      render_systemd_timer "$daily_at"
      return 0
    fi
    require_command systemctl || return $?
    mkdir -p "$unit_dir"
    render_systemd_service "$selected_backup_dir" > "$service_file"
    render_systemd_timer "$daily_at" > "$timer_file"
    chmod 600 "$service_file" "$timer_file"
    if ! systemctl --user daemon-reload; then
      echo "Nie mozna polaczyc sie z menedzerem systemd uzytkownika." >&2
      echo "Sprawdz, czy systemd jest wlaczony i wykonaj ponownie polecenie po ponownym uruchomieniu WSL." >&2
      return 1
    fi
    if ! systemctl --user enable --now "${SYSTEMD_UNIT_NAME}.timer"; then
      echo "Nie udalo sie uruchomic timera ${SYSTEMD_UNIT_NAME}.timer." >&2
      systemctl --user status "${SYSTEMD_UNIT_NAME}.timer" --no-pager >&2 || true
      return 1
    fi
    echo "Zainstalowano harmonogram systemd: codziennie o $daily_at."
    echo "Katalog kopii: $selected_backup_dir"
    systemctl --user list-timers "${SYSTEMD_UNIT_NAME}.timer" --no-pager
    if command -v loginctl >/dev/null 2>&1; then
      local linger_status
      linger_status="$(loginctl show-user "$USER" -p Linger --value 2>/dev/null || true)"
      if [[ "$linger_status" != "yes" ]]; then
        echo ""
        echo "UWAGA: linger dla uzytkownika $USER nie jest wlaczony."
        echo "Aby timer dzialal po zamknieciu sesji, wykonaj jednorazowo:"
        echo "  sudo loginctl enable-linger $USER"
      fi
    fi
  else
    local agents_dir="$HOME/Library/LaunchAgents"
    local logs_dir="$HOME/Library/Logs"
    local plist_file="$agents_dir/${LAUNCHD_LABEL}.plist"
    if [[ "$dry_run" == "true" ]]; then
      echo "--- $plist_file ---"
      render_launchd_plist "$selected_backup_dir" "$daily_at"
      return 0
    fi
    require_command launchctl || return $?
    mkdir -p "$agents_dir" "$logs_dir"
    launchctl bootout "gui/$UID" "$plist_file" >/dev/null 2>&1 || true
    render_launchd_plist "$selected_backup_dir" "$daily_at" > "$plist_file"
    chmod 600 "$plist_file"
    launchctl bootstrap "gui/$UID" "$plist_file"
    launchctl enable "gui/$UID/$LAUNCHD_LABEL"
    echo "Zainstalowano harmonogram launchd: codziennie o $daily_at."
    echo "Katalog kopii: $selected_backup_dir"
  fi
}

schedule_status() {
  local platform
  platform="$(schedule_platform)" || return $?
  if [[ "$platform" == "systemd" ]]; then
    systemctl --user status "${SYSTEMD_UNIT_NAME}.timer" --no-pager
    echo ""
    systemctl --user list-timers "${SYSTEMD_UNIT_NAME}.timer" --no-pager
  else
    launchctl print "gui/$UID/$LAUNCHD_LABEL"
  fi
}

schedule_run_now() {
  local platform
  platform="$(schedule_platform)" || return $?
  if [[ "$platform" == "systemd" ]]; then
    if ! systemctl --user start "${SYSTEMD_UNIT_NAME}.service"; then
      echo "Zaplanowany backup zakonczyl sie bledem. Ostatnie informacje:" >&2
      systemctl --user show "${SYSTEMD_UNIT_NAME}.service" \
        -p Result -p ExecMainStatus -p ExecMainStartTimestamp -p ExecMainExitTimestamp --no-pager >&2 || true
      journalctl --user -u "${SYSTEMD_UNIT_NAME}.service" -n 50 --no-pager >&2 || true
      return 1
    fi
    systemctl --user show "${SYSTEMD_UNIT_NAME}.service" \
      -p Result -p ExecMainStatus -p ExecMainStartTimestamp -p ExecMainExitTimestamp --no-pager
  else
    launchctl kickstart -k "gui/$UID/$LAUNCHD_LABEL"
    echo "Uruchomiono zadanie $LAUNCHD_LABEL."
  fi
}

schedule_logs() {
  local platform
  platform="$(schedule_platform)" || return $?
  if [[ "$platform" == "systemd" ]]; then
    journalctl --user -u "${SYSTEMD_UNIT_NAME}.service" -n 150 --no-pager
  else
    echo "--- standard output ---"
    tail -n 150 "$HOME/Library/Logs/novabank-backup.log" 2>/dev/null || echo "Brak logu standardowego."
    echo "--- errors ---"
    tail -n 150 "$HOME/Library/Logs/novabank-backup-error.log" 2>/dev/null || echo "Brak logu bledow."
  fi
}

schedule_remove() {
  local platform
  platform="$(schedule_platform)" || return $?
  if [[ "$platform" == "systemd" ]]; then
    local unit_dir="$HOME/.config/systemd/user"
    systemctl --user disable --now "${SYSTEMD_UNIT_NAME}.timer" >/dev/null 2>&1 || true
    systemctl --user stop "${SYSTEMD_UNIT_NAME}.service" >/dev/null 2>&1 || true
    rm -f "$unit_dir/${SYSTEMD_UNIT_NAME}.timer" "$unit_dir/${SYSTEMD_UNIT_NAME}.service"
    systemctl --user daemon-reload
    systemctl --user reset-failed >/dev/null 2>&1 || true
    echo "Usunieto harmonogram systemd NovaBank."
  else
    local plist_file="$HOME/Library/LaunchAgents/${LAUNCHD_LABEL}.plist"
    launchctl bootout "gui/$UID" "$plist_file" >/dev/null 2>&1 || true
    rm -f "$plist_file"
    echo "Usunieto harmonogram launchd NovaBank."
  fi
}

run_interactive_worker() {
  local result
  if create_backup; then
    result=0
    printf '\nProcedura backupu zakonczona poprawnie.\n'
  else
    result=$?
    printf '\nProcedura backupu zakonczyla sie bledem. Sprawdz podsumowanie powyzej.\n'
  fi
  printf 'Raport znajduje sie w katalogu wykonanej kopii.\n'
  printf 'Zapoznaj sie z raportem, a nastepnie wpisz exit, aby zamknac okno.\n\n'
  export NOVABANK_BACKUP_EXIT_CODE="$result"
  exec bash -i
}

if (($# == 0)); then
  launch_unix
  exit $?
fi

COMMAND="$1"
shift
case "$COMMAND" in
  create)
    create_backup "$@"
    ;;
  restore-test)
    restore_test_command "$@"
    ;;
  dr-test)
    disaster_recovery_command "$@"
    ;;
  dr-report)
    dr_report_command "$@"
    ;;
  prune)
    create_backup --prune "$@"
    ;;
  schedule-install)
    schedule_install "$@"
    ;;
  schedule-status)
    schedule_status
    ;;
  schedule-run-now)
    schedule_run_now
    ;;
  schedule-logs)
    schedule_logs
    ;;
  schedule-remove)
    schedule_remove
    ;;
  dr-schedule-install)
    dr_schedule_install "$@"
    ;;
  dr-schedule-status)
    dr_schedule_status
    ;;
  dr-schedule-run-now)
    dr_schedule_run_now
    ;;
  dr-schedule-logs)
    dr_schedule_logs
    ;;
  dr-schedule-remove)
    dr_schedule_remove
    ;;
  internal-worker)
    run_interactive_worker
    ;;
  --help|-h|help)
    usage
    ;;
  *)
    echo "Nieznane polecenie: $COMMAND" >&2
    usage >&2
    exit 2
    ;;
esac
