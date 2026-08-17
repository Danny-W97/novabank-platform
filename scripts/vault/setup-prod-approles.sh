#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
POLICY_DIR="$SCRIPT_DIR/policies"

: "${VAULT_ADDR:?Ustaw VAULT_ADDR przed uruchomieniem skryptu}"
: "${VAULT_CACERT:?Ustaw VAULT_CACERT przed uruchomieniem skryptu}"

vault policy write bank-prod "$POLICY_DIR/bank-prod.hcl"
vault policy write shop-prod "$POLICY_DIR/shop-prod.hcl"

vault write auth/approle/role/bank-prod \
  token_type=service \
  token_policies=bank-prod \
  token_ttl=1h \
  token_max_ttl=24h \
  secret_id_ttl=24h \
  secret_id_num_uses=5

vault write auth/approle/role/shop-prod \
  token_type=service \
  token_policies=shop-prod \
  token_ttl=1h \
  token_max_ttl=24h \
  secret_id_ttl=24h \
  secret_id_num_uses=5

echo "Polityki i role AppRole zostaly skonfigurowane."
echo "Role ID i Secret ID nie sa wyswietlane przez ten skrypt."
echo "Aby wygenerowac, przetestowac i zapisac dane bootstrap, uruchom:"
echo "  bash scripts/vault/approle-secret-id.sh rotate all"
