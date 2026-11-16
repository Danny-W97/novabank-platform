#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STATE_FILE="$PROJECT_ROOT/.novabank/env/approle-secret-id-rotation.env"
mkdir -p "$(dirname "$STATE_FILE")"

COMMAND=""
TARGET="all"

usage() {
  cat <<'EOF'
NovaBank - rotacja AppRole Secret ID dla profilu prod

Uzycie:
  bash scripts/vault/approle-secret-id.sh rotate [bank|shop|all]
  bash scripts/vault/approle-secret-id.sh finalize [bank|shop|all]

Przeplyw:
  1. rotate tworzy nowy Secret ID, testuje logowanie i odczyt sekretu,
     a nastepnie atomowo aktualizuje odpowiedni plik bootstrap.
  2. Zrestartuj aplikacje prod i sprawdz jej polaczenie z Vault.
  3. finalize uniewaznia poprzedni Secret ID zapisany przez accessor.

Skrypt nie wyswietla Role ID, Secret ID ani tokenu testowego.
EOF
}

fail() {
  printf 'BLAD: %s\n' "$*" >&2
  exit 1
}

warn() {
  printf 'OSTRZEZENIE: %s\n' "$*" >&2
}

if [[ $# -gt 0 ]]; then
  COMMAND="$1"
  shift
fi
if [[ $# -gt 0 ]]; then
  TARGET="$1"
  shift
fi
[[ $# -eq 0 ]] || fail "Podano zbyt wiele argumentow."

case "$COMMAND" in
  rotate|finalize) ;;
  -h|--help|"") usage; [[ -n "$COMMAND" ]] && exit 0 || exit 1 ;;
  *) fail "Nieznane polecenie: $COMMAND" ;;
esac

case "$TARGET" in
  bank|shop|all) ;;
  *) fail "Nieznany cel: $TARGET. Uzyj bank, shop albo all." ;;
esac

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Brak polecenia '$1'."
}

require_command vault
require_command jq
require_command awk
require_command mktemp

: "${VAULT_ADDR:?Ustaw VAULT_ADDR, np. https://127.0.0.1:8200}"
: "${VAULT_CACERT:?Ustaw VAULT_CACERT na certyfikat CA Vault w WSL}"

vault_status="$(vault status -format=json 2>/dev/null || true)"
[[ -n "$vault_status" ]] || fail "Vault nie odpowiada pod VAULT_ADDR=$VAULT_ADDR."
[[ "$(printf '%s' "$vault_status" | jq -r '.initialized')" == "true" ]] || fail "Vault nie jest zainicjalizowany."
[[ "$(printf '%s' "$vault_status" | jq -r '.sealed')" == "false" ]] || fail "Vault jest zapieczetowany. Odpieczetuj go trzema udzialami unseal."
vault token lookup >/dev/null 2>&1 || fail "Brak aktywnego tokenu operatora. Wykonaj vault login kontem uprawnionym do zarzadzania AppRole."

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

is_configured_value() {
  local value="${1:-}"
  [[ -n "$value" ]] || return 1
  case "$value" in
    CHANGE_ME|change-me-*|replace-with-*|RZECZYWISTY_*|\<*\>) return 1 ;;
  esac
  return 0
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
  chmod 600 "$file" 2>/dev/null || true
}

remove_env_key() {
  local file="$1"
  local key="$2"
  local temp

  [[ -f "$file" ]] || return 0
  temp="$(mktemp "${file}.tmp.XXXXXX")"
  awk -v key="$key" 'index($0, key "=") != 1 { sub(/\r$/, ""); print }' "$file" > "$temp"
  mv "$temp" "$file"

  if ! grep -Eq '^[A-Z][A-Z0-9_]*=' "$file"; then
    rm -f "$file"
  fi
}

role_config() {
  local app="$1"
  case "$app" in
    bank)
      ROLE_NAME="bank-prod"
      BOOTSTRAP_FILE="$PROJECT_ROOT/.novabank/env/prod.bank.env"
      SECRET_PATH="secret/Bank-demo/prod"
      STATE_KEY="BANK_PREVIOUS_SECRET_ID_ACCESSOR"
      ;;
    shop)
      ROLE_NAME="shop-prod"
      BOOTSTRAP_FILE="$PROJECT_ROOT/.novabank/env/prod.shop.env"
      SECRET_PATH="secret/shop-app/prod"
      STATE_KEY="SHOP_PREVIOUS_SECRET_ID_ACCESSOR"
      ;;
  esac
}

destroy_secret_value() {
  local role_name="$1"
  local secret_id="$2"
  printf '%s' "$secret_id" |
    vault write "auth/approle/role/$role_name/secret-id/destroy" secret_id=- >/dev/null 2>&1 || true
}

lookup_secret_accessor() {
  local role_name="$1"
  local secret_id="$2"
  local response

  response="$(printf '%s' "$secret_id" |
    vault write -format=json "auth/approle/role/$role_name/secret-id/lookup" secret_id=- 2>/dev/null)" || return 1
  printf '%s' "$response" | jq -er '.data.secret_id_accessor'
}

test_new_secret() {
  local role_id="$1"
  local secret_id="$2"
  local secret_path="$3"
  local login_response client_token

  login_response="$(printf '%s' "$secret_id" |
    vault write -format=json auth/approle/login role_id="$role_id" secret_id=- 2>/dev/null)" || return 1
  client_token="$(printf '%s' "$login_response" | jq -er '.auth.client_token')" || return 1

  if ! VAULT_TOKEN="$client_token" vault kv get -format=json "$secret_path" >/dev/null 2>&1; then
    VAULT_TOKEN="$client_token" vault token revoke -self >/dev/null 2>&1 || true
    return 1
  fi

  if ! VAULT_TOKEN="$client_token" vault token revoke -self >/dev/null 2>&1; then
    warn "Nie udalo sie od razu uniewaznic testowego tokenu; wygasnie zgodnie z TTL roli."
    return 1
  fi

  return 0
}

rotate_role() {
  local app="$1"
  local role_id old_secret old_accessor existing_state
  local generation new_secret new_accessor ttl uses remaining

  role_config "$app"
  if [[ ! -f "$BOOTSTRAP_FILE" ]]; then
    mkdir -p "$(dirname "$BOOTSTRAP_FILE")"
    touch "$BOOTSTRAP_FILE"
    chmod 600 "$BOOTSTRAP_FILE" 2>/dev/null || true
    warn "Utworzono minimalny plik $BOOTSTRAP_FILE. Przed startem aplikacji uzupelnij w nim ustawienia truststore zgodnie z instrukcja prod."
  fi

  existing_state="$(read_env_value "$STATE_FILE" "$STATE_KEY")"
  [[ -z "$existing_state" ]] || fail "Dla $ROLE_NAME istnieje niezakonczona rotacja. Zrestartuj aplikacje i wykonaj finalize $app przed kolejna rotacja."

  role_id="$(vault read -field=role_id "auth/approle/role/$ROLE_NAME/role-id")"
  [[ -n "$role_id" ]] || fail "Nie udalo sie pobrac Role ID dla $ROLE_NAME."

  old_secret="$(read_env_value "$BOOTSTRAP_FILE" VAULT_SECRET_ID)"
  old_accessor=""
  if is_configured_value "$old_secret"; then
    old_accessor="$(lookup_secret_accessor "$ROLE_NAME" "$old_secret" || true)"
  fi

  generation="$(vault write -f -format=json "auth/approle/role/$ROLE_NAME/secret-id")"
  new_secret="$(printf '%s' "$generation" | jq -er '.data.secret_id')"
  new_accessor="$(printf '%s' "$generation" | jq -er '.data.secret_id_accessor')"
  ttl="$(printf '%s' "$generation" | jq -r '.data.secret_id_ttl // "nieznany"')"
  uses="$(printf '%s' "$generation" | jq -r '.data.secret_id_num_uses // 0')"

  if ! test_new_secret "$role_id" "$new_secret" "$SECRET_PATH"; then
    destroy_secret_value "$ROLE_NAME" "$new_secret"
    fail "Test nowego Secret ID dla $ROLE_NAME nie powiodl sie. Plik bootstrap nie zostal zmieniony, a nowy identyfikator zostal uniewazniony."
  fi

  upsert_env_value "$BOOTSTRAP_FILE" VAULT_ADDR "$VAULT_ADDR"
  upsert_env_value "$BOOTSTRAP_FILE" VAULT_ROLE "$ROLE_NAME"
  upsert_env_value "$BOOTSTRAP_FILE" VAULT_ROLE_ID "$role_id"
  upsert_env_value "$BOOTSTRAP_FILE" VAULT_SECRET_ID "$new_secret"
  upsert_env_value "$BOOTSTRAP_FILE" VAULT_APPROLE_PATH "approle"

  if [[ -n "$old_accessor" && "$old_accessor" != "$new_accessor" ]]; then
    upsert_env_value "$STATE_FILE" "$STATE_KEY" "$old_accessor"
  fi

  remaining="$uses"
  if [[ "$uses" =~ ^[0-9]+$ && "$uses" -gt 0 ]]; then
    remaining=$((uses - 1))
  fi

  printf '\nAppRole:                 %s\n' "$ROLE_NAME"
  printf 'Plik bootstrap:          %s\n' "$BOOTSTRAP_FILE"
  printf 'Test logowania i odczytu: OK\n'
  printf 'TTL Secret ID:           %s s\n' "$ttl"
  printf 'Limit uzyc po utworzeniu: %s\n' "$uses"
  printf 'Szacowane pozostale:     %s (jedno uzycie zuzyl test)\n' "$remaining"
  if [[ -n "$old_accessor" ]]; then
    printf 'Poprzedni Secret ID:     aktywny do wykonania finalize lub do wygasniecia\n'
  else
    printf 'Poprzedni Secret ID:     brak albo byl juz niewazny\n'
  fi
  printf 'Sekrety wyswietlone:     NIE\n'
}

finalize_role() {
  local app="$1"
  local accessor lookup_response

  role_config "$app"
  accessor="$(read_env_value "$STATE_FILE" "$STATE_KEY")"
  if [[ -z "$accessor" ]]; then
    printf '%s: brak poprzedniego Secret ID oczekujacego na uniewaznienie.\n' "$ROLE_NAME"
    return 0
  fi

  if lookup_response="$(vault write -format=json \
      "auth/approle/role/$ROLE_NAME/secret-id-accessor/lookup" \
      secret_id_accessor="$accessor" 2>/dev/null)"; then
    vault write \
      "auth/approle/role/$ROLE_NAME/secret-id-accessor/destroy" \
      secret_id_accessor="$accessor" >/dev/null
    printf '%s: poprzedni Secret ID zostal uniewazniony.\n' "$ROLE_NAME"
  else
    printf '%s: poprzedni Secret ID juz nie istnieje albo wygasl; lokalny stan zostanie wyczyszczony.\n' "$ROLE_NAME"
  fi

  remove_env_key "$STATE_FILE" "$STATE_KEY"
}

apps=()
if [[ "$TARGET" == "all" ]]; then
  apps=(bank shop)
else
  apps=("$TARGET")
fi

case "$COMMAND" in
  rotate)
    for app in "${apps[@]}"; do
      role_config "$app"
      pending_accessor="$(read_env_value "$STATE_FILE" "$STATE_KEY")"
      [[ -z "$pending_accessor" ]] || fail "Dla $ROLE_NAME istnieje niezakonczona rotacja. Wykonaj finalize $app przed rozpoczeciem kolejnej."
    done
    printf 'Rozpoczynam bezpieczna rotacje AppRole Secret ID.\n'
    for app in "${apps[@]}"; do
      rotate_role "$app"
    done
    printf '\nRotacja zakonczona. Zrestartuj wskazane aplikacje prod i sprawdz ich start.\n'
    printf 'Po poprawnym tescie wykonaj: bash scripts/vault/approle-secret-id.sh finalize %s\n' "$TARGET"
    ;;
  finalize)
    printf 'Uniewaznianie poprzednich AppRole Secret ID.\n'
    for app in "${apps[@]}"; do
      finalize_role "$app"
    done
    ;;
esac
