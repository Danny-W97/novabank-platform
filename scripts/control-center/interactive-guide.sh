#!/usr/bin/env bash
set -u

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
GUIDE="$PROJECT_ROOT/docs/interactive-guide.html"

if [[ ! -f "$GUIDE" ]]; then
  printf 'Nie znaleziono przewodnika: %s\n' "$GUIDE" >&2
  exit 1
fi

printf 'NovaBank - interaktywne wprowadzenie\n'
printf 'Plik: %s\n' "$GUIDE"

if command -v explorer.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
  explorer.exe "$(wslpath -w "$GUIDE")" >/dev/null 2>&1 &
  printf 'Przewodnik otwarto w przegladarce Windows.\n'
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$GUIDE" >/dev/null 2>&1 &
  printf 'Przewodnik otwarto w domyslnej przegladarce.\n'
elif command -v open >/dev/null 2>&1; then
  open "$GUIDE" >/dev/null 2>&1 &
  printf 'Przewodnik otwarto w domyslnej przegladarce.\n'
else
  printf 'Nie znaleziono polecenia otwierajacego przegladarke.\n'
  printf 'Otworz recznie plik: %s\n' "$GUIDE"
fi
