#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ "${1:-}" == "--cli" ]]; then
  shift
  exec bash "$SCRIPT_DIR/control-center/control-center.sh" "$@"
fi
PYTHON="${NOVABANK_CONTROL_PYTHON:-python3}"
command -v "$PYTHON" >/dev/null 2>&1 || { printf 'Wymagany Python 3.10+ (NOVABANK_CONTROL_PYTHON).\n' >&2; exit 1; }
exec "$PYTHON" -X utf8 "$SCRIPT_DIR/control-center/web/server.py" "$@"
