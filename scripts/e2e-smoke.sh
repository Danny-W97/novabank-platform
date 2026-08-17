#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEV_ENV="$PROJECT_ROOT/.novabank/env/dev.env"

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

BANK_URL="${BANK_URL:-http://127.0.0.1:8080}"
SHOP_URL="${SHOP_URL:-http://127.0.0.1:8081}"
BANK_HEALTH_URL="${BANK_HEALTH_URL:-http://127.0.0.1:8082/actuator/health}"
SHOP_HEALTH_URL="${SHOP_HEALTH_URL:-http://127.0.0.1:8083/actuator/health}"
if [[ -z "${BANK_API_KEY:-}" ]]; then
  BANK_API_KEY="$(read_env_value "$DEV_ENV" BANK_API_KEY)"
fi
BANK_API_KEY="${BANK_API_KEY:-ci-bank-key}"
if [[ -z "${BANK_API_KEY_ID:-}" ]]; then
  BANK_API_KEY_ID="$(read_env_value "$DEV_ENV" BANK_API_KEY_ID)"
fi
BANK_API_KEY_ID="${BANK_API_KEY_ID:-legacy}"
E2E_PAYMENT_IDEMPOTENCY_KEY="${E2E_PAYMENT_IDEMPOTENCY_KEY:-ci-e2e-payment-$(date +%s)-${RANDOM}-$$}"
E2E_WAIT_TIMEOUT_SECONDS="${E2E_WAIT_TIMEOUT_SECONDS:-90}"
E2E_WAIT_INTERVAL_SECONDS="${E2E_WAIT_INTERVAL_SECONDS:-2}"

WORK_DIR="$(mktemp -d)"
COOKIE_JAR="$WORK_DIR/shop.cookies"
RESPONSE="$WORK_DIR/response.json"
HEADERS="$WORK_DIR/headers.txt"

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

wait_for_http() {
  local url="$1"
  local deadline=$((SECONDS + E2E_WAIT_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    if curl --silent --show-error --fail --connect-timeout 2 --max-time 5 "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$E2E_WAIT_INTERVAL_SECONDS"
  done
  echo "Timed out after ${E2E_WAIT_TIMEOUT_SECONDS}s waiting for $url" >&2
  echo "Health endpoint diagnostics:" >&2
  curl --silent --show-error --connect-timeout 2 --max-time 5 -i "$url" >&2 || true
  return 1
}

assert_status() {
  local expected="$1"
  local actual="$2"
  local operation="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "$operation returned HTTP $actual, expected HTTP $expected" >&2
    cat "$RESPONSE" >&2 || true
    exit 1
  fi
}

echo "Waiting for bank and shop health endpoints..."
wait_for_http "$BANK_HEALTH_URL" || exit 1
wait_for_http "$SHOP_HEALTH_URL" || exit 1

bank_login_status="$(curl --silent --show-error --output "$RESPONSE" --write-out '%{http_code}' "$BANK_URL/login")"
assert_status "200" "$bank_login_status" "Bank login page"

products="$(curl --silent --show-error --fail "$SHOP_URL/api/products")"
product_id="$(jq -er '.[0].id' <<<"$products")"
if [[ -z "$product_id" || "$product_id" == "null" ]]; then
  echo "The shop catalog returned no product." >&2
  exit 1
fi

csrf="$(curl --silent --show-error --fail \
  --cookie-jar "$COOKIE_JAR" --cookie "$COOKIE_JAR" \
  "$SHOP_URL/api/auth/csrf" | jq -er '.token')"

email="e2e-$(date +%s)-${RANDOM}@example.com"
password='E2eTest@2026!'

register_status="$(curl --silent --show-error --output "$RESPONSE" --write-out '%{http_code}' \
  --cookie-jar "$COOKIE_JAR" --cookie "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $csrf" \
  -d "{\"email\":\"$email\",\"password\":\"$password\",\"passwordConfirmation\":\"$password\"}" \
  "$SHOP_URL/api/auth/register")"
assert_status "201" "$register_status" "Shop registration"

login_status="$(curl --silent --show-error --output "$RESPONSE" --write-out '%{http_code}' \
  --cookie-jar "$COOKIE_JAR" --cookie "$COOKIE_JAR" \
  -H "X-XSRF-TOKEN: $csrf" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "username=$email" \
  --data-urlencode "password=$password" \
  "$SHOP_URL/login")"
assert_status "302" "$login_status" "Shop login"

me_status="$(curl --silent --show-error --output "$RESPONSE" --write-out '%{http_code}' \
  --cookie-jar "$COOKIE_JAR" --cookie "$COOKIE_JAR" \
  "$SHOP_URL/api/auth/me")"
assert_status "200" "$me_status" "Shop authenticated session"

# Spring Security odnawia token CSRF po uwierzytelnieniu, dlatego operacje
# koszyka musza korzystac z tokenu pobranego juz dla zalogowanej sesji.
csrf="$(curl --silent --show-error --fail \
  --cookie-jar "$COOKIE_JAR" --cookie "$COOKIE_JAR" \
  "$SHOP_URL/api/auth/csrf" | jq -er '.token')"

add_status="$(curl --silent --show-error --output "$RESPONSE" --write-out '%{http_code}' \
  --cookie-jar "$COOKIE_JAR" --cookie "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $csrf" \
  -d "{\"productId\":$product_id,\"quantity\":1}" \
  "$SHOP_URL/api/cart/items")"
assert_status "204" "$add_status" "Add product to shop cart"

order_status="$(curl --silent --show-error --output "$RESPONSE" --write-out '%{http_code}' \
  --cookie-jar "$COOKIE_JAR" --cookie "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $csrf" \
  -d '{}' "$SHOP_URL/api/orders")"
assert_status "201" "$order_status" "Create shop order"
order_id="$(jq -er '.id' "$RESPONSE")"

payment_status="$(curl --silent --show-error --output "$RESPONSE" --write-out '%{http_code}' \
  --cookie-jar "$COOKIE_JAR" --cookie "$COOKIE_JAR" \
  -H "X-XSRF-TOKEN: $csrf" \
  -H "Idempotency-Key: $E2E_PAYMENT_IDEMPOTENCY_KEY" \
  -X POST "$SHOP_URL/api/orders/$order_id/pay")"
assert_status "200" "$payment_status" "Shop-to-bank payment authorization"

payment_state="$(jq -er '.status' "$RESPONSE")"
if [[ "$payment_state" != "PENDING" ]]; then
  echo "Expected a pending bank authorization, received $payment_state." >&2
  cat "$RESPONSE" >&2
  exit 1
fi

authorization_url="$(jq -er '.authorizationUrl' "$RESPONSE")"

repeat_payment_status="$(curl --silent --show-error --output "$RESPONSE" --write-out '%{http_code}' \
  --cookie-jar "$COOKIE_JAR" --cookie "$COOKIE_JAR" \
  -H "X-XSRF-TOKEN: $csrf" \
  -H "Idempotency-Key: $E2E_PAYMENT_IDEMPOTENCY_KEY" \
  -X POST "$SHOP_URL/api/orders/$order_id/pay")"
assert_status "200" "$repeat_payment_status" "Repeated shop-to-bank payment authorization"
repeat_authorization_url="$(jq -er '.authorizationUrl' "$RESPONSE")"
if [[ "$repeat_authorization_url" != "$authorization_url" ]]; then
  echo "Repeated payment created a different authorization despite the same Idempotency-Key." >&2
  exit 1
fi

authorization_id="${authorization_url##*/}"
if [[ -z "$authorization_id" || "$authorization_id" == "$authorization_url" ]]; then
  echo "Shop response did not contain a usable bank authorization URL." >&2
  exit 1
fi

authorization_status="$(curl --silent --show-error --output "$RESPONSE" --write-out '%{http_code}' \
  -H "X-API-Key: $BANK_API_KEY" \
  -H "X-API-Key-Id: $BANK_API_KEY_ID" \
  "$BANK_URL/api/payment-authorizations/$authorization_id/status")"
assert_status "200" "$authorization_status" "Bank authorization status"
if [[ "$(jq -er '.status' "$RESPONSE")" != "PENDING" ]]; then
  echo "Bank authorization was not pending." >&2
  cat "$RESPONSE" >&2
  exit 1
fi

echo "E2E smoke test passed: bank health, shop health, shop auth, cart, order and shop-to-bank authorization."
