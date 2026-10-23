#!/usr/bin/env bash
# Non-interactive local operations. Called only after the server's allowlist gate.
set -Eeuo pipefail
umask 077
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
action="${1:-}" profile="${2:-}"
[[ $# == 2 ]] || exit 2

case "$profile:$action" in
  dev:monitoring-*|dev:backup-*|dev:dr-*|dev:stripe-*|dev:install-stripe) ;;
  dev:prerequisites|test:prerequisites|dev:install-tools|test:install-tools) ;;
  *) printf 'Operacja nie należy do lokalnego DEV/TEST.\n' >&2; exit 2 ;;
esac

local_docker() {
  command -v docker >/dev/null || { printf 'Brak Docker.\n' >&2; return 1; }
  local endpoint="${DOCKER_HOST:-}"
  [[ -n "$endpoint" ]] || endpoint="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
  case "$endpoint" in unix://*|npipe://*) ;; *) printf 'Wymagany lokalny socket Docker, nie SSH/TCP.\n' >&2; return 1 ;; esac
}

case "$action" in
  prerequisites)
    result=0
    for binary in bash python3 java javac keytool docker curl jq openssl gpg; do
      if command -v "$binary" >/dev/null; then printf '%-12s OK\n' "$binary"; else printf '%-12s BRAK\n' "$binary"; result=1; fi
    done
    for binary in stripe npm systemctl; do
      if command -v "$binary" >/dev/null; then printf '%-12s OK (opcjonalne)\n' "$binary"; else printf '%-12s brak (opcjonalne)\n' "$binary"; fi
    done
    printf 'Vault/TLS PROD nie są wymagane. Docker i JDK 25 przygotuj w WSL.\n'
    exit "$result" ;;
  install-tools)
    command -v apt-get >/dev/null || { printf 'Instalacja wspiera Ubuntu/Debian.\n' >&2; exit 1; }
    command -v sudo >/dev/null && sudo -n true || { printf 'Brak nieinteraktywnego sudo. Administrator musi udzielić uprawnień w systemie; panel nie przyjmuje hasła sudo.\n' >&2; exit 1; }
    sudo -n apt-get update
    sudo -n apt-get install -y ca-certificates curl wget jq openssl gnupg python3-venv ;;
  install-stripe)
    command -v npm >/dev/null || { printf 'Brak npm. Zainstaluj Node/npm w WSL.\n' >&2; exit 1; }
    npm install -g @stripe/cli ;;
  monitoring-*)
    local_docker
    for entry in .novabank .novabank/monitoring .novabank/monitoring/prometheus.yml; do
      [[ ! -L "$task_root/$entry" ]] || { printf 'Monitoring panelu nie może używać dowiązań.\n' >&2; exit 1; }
    done
    case "$action" in
      monitoring-setup) operation=(setup --profile dev) ;;
      monitoring-start) operation=(start --profile dev) ;;
      monitoring-stop) operation=(stop --profile dev) ;;
      monitoring-status) operation=(status --profile dev) ;;
      monitoring-rotate) operation=(setup --profile dev --force) ;;
      monitoring-logs-prometheus) operation=(logs prometheus) ;;
      monitoring-logs-grafana) operation=(logs grafana) ;;
      monitoring-logs-alertmanager) operation=(logs alertmanager) ;;
      *) exit 2 ;;
    esac
    bash "$task_root/scripts/monitoring/monitoring.sh" "${operation[@]}" ;;
  backup-*|dr-*)
    local_docker
    for directory in .novabank .novabank/backups .novabank/backups/dev; do
      [[ ! -L "$task_root/$directory" ]] || { printf 'Backupy panelu nie mogą używać dowiązań.\n' >&2; exit 1; }
    done
    if [[ "$action" == *schedule* && "$(uname -s)" != Linux ]]; then
      printf 'Harmonogramy panelu wymagają systemd użytkownika w WSL/Linux.\n' >&2; exit 1
    fi
    backup_directory="${NOVABANK_WEB_BACKUP_DIRECTORY:-$task_root/.novabank/backups/dev}"
    backup_at="${NOVABANK_WEB_BACKUP_AT:-02:00}"
    dr_at="${NOVABANK_WEB_DR_AT:-04:00}"
    dr_weekday="${NOVABANK_WEB_DR_WEEKDAY:-SUN}"
    owner_operation=check-backup
    case "$action" in backup-create|backup-prune|backup-restore-test|dr-test|backup-schedule-install|dr-schedule-install) owner_operation=prepare-backup ;; esac
    python3 -B "$task_root/scripts/control-center/web/operator_settings.py" "$owner_operation" "$task_root" "$backup_directory" >/dev/null
    case "$action" in
      backup-create) operation=(create --backup-dir "$backup_directory") ;;
      backup-restore-test) operation=(restore-test --backup-dir "$backup_directory") ;;
      backup-prune) operation=(prune --backup-dir "$backup_directory") ;;
      backup-schedule-install) operation=(schedule-install --backup-dir "$backup_directory" --daily-at "$backup_at") ;;
      backup-schedule-status) operation=(schedule-status) ;;
      backup-schedule-run-now) operation=(schedule-run-now) ;;
      backup-schedule-logs) operation=(schedule-logs) ;;
      backup-schedule-remove) operation=(schedule-remove) ;;
      dr-test|dr-report) operation=("$action" --backup-dir "$backup_directory") ;;
      dr-schedule-install) operation=(dr-schedule-install --backup-dir "$backup_directory" --weekday "$dr_weekday" --at "$dr_at") ;;
      dr-schedule-status|dr-schedule-run-now|dr-schedule-logs|dr-schedule-remove) operation=("$action") ;;
      *) exit 2 ;;
    esac
    if [[ "${NOVABANK_WEB_TIMER_DRY_RUN:-}" == 1 ]]; then
      case "$action" in
        backup-schedule-install|dr-schedule-install) operation+=(--dry-run) ;;
        *) printf 'Dry-run dotyczy wyłącznie renderowania timerów.\n' >&2; exit 2 ;;
      esac
    fi
    # Fixed input/output/container scope; env is parsed as data by backup.sh.
    NOVABANK_WEB_MODE=1 bash "$task_root/scripts/backup/backup.sh" "${operation[@]}" ;;
  stripe-status)
    stripe version
    stripe config --list ;;
  stripe-login)
    exec python3 -B "$task_root/scripts/control-center/web/stripe_local.py" login ;;
  stripe-listen)
    exec python3 -B "$task_root/scripts/control-center/web/stripe_local.py" listen ;;
  stripe-cancellation-test)
    exec python3 -B "$task_root/scripts/control-center/web/stripe_local.py" cancellation-contract ;;
  stripe-secret)
    exec python3 -B "$task_root/scripts/control-center/web/stripe_local.py" secret ;;
  *) exit 2 ;;
esac
