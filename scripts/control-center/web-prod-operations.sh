#!/usr/bin/env bash
# Fixed, non-interactive installer only. No token, unseal key or shell from HTTP.
set -Eeuo pipefail
umask 077
[[ $# -eq 2 && "$1" == install-vault && "$2" == prod ]] || { printf 'Niedozwolona operacja PROD.\n' >&2; exit 2; }
if command -v vault >/dev/null; then vault version; exit 0; fi
command -v apt-get >/dev/null && command -v sudo >/dev/null && sudo -n true || { printf 'Wymagane Ubuntu/Debian oraz sudo -n. Panel nie przyjmuje hasła administratora.\n' >&2; exit 1; }
for command in curl gpg dpkg; do command -v "$command" >/dev/null || { printf 'Brak narzędzia: %s\n' "$command"; exit 1; }; done
codename="$(. /etc/os-release; printf '%s' "${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}")"
[[ "$codename" =~ ^[a-z0-9-]+$ ]] || { printf 'Nieznana dystrybucja.\n' >&2; exit 1; }
temporary="$(mktemp -d)"
trap 'rm -f -- "$temporary/key.asc" "$temporary/key.gpg"; rmdir -- "$temporary"' EXIT
curl --fail --silent --show-error --max-time 30 https://apt.releases.hashicorp.com/gpg -o "$temporary/key.asc"
gpg --batch --yes --dearmor -o "$temporary/key.gpg" "$temporary/key.asc"
sudo -n install -m 0644 "$temporary/key.gpg" /usr/share/keyrings/hashicorp-archive-keyring.gpg
printf 'deb [arch=%s signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com %s main\n' "$(dpkg --print-architecture)" "$codename" | sudo -n tee /etc/apt/sources.list.d/hashicorp.list >/dev/null
sudo -n apt-get update
sudo -n apt-get install -y vault
vault version
printf 'Zainstalowano CLI/usługę pakietu. Konfigurację TLS, inicjalizację i ochronę udziałów przygotuj zgodnie z instrukcją. Panel nie wykonał unseal ani init.\n'
