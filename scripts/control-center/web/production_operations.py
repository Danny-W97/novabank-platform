"""Explicit, localhost-only PROD-like procedures. Never logs request/response secrets.

Vault API paths: HashiCorp sys/unseal, sys/policies/acl and auth/approle docs.
Keytool password modifiers: Oracle JDK 25 keytool manual (-storepass:env).
No root token, arbitrary URL, shell command, seal/rekey/init or business KV editor.
"""
import json
import os
import re
import shutil
import ssl
import subprocess
import tempfile
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, HTTPSHandler, ProxyHandler, HTTPRedirectHandler

from configuration_files import ConfigurationFiles
from dev_configuration import assignments, upsert

ADDRESS = "https://127.0.0.1:8200"
FILES = {"bank": ".novabank/env/prod.bank.env", "shop": ".novabank/env/prod.shop.env",
         "rotation": ".novabank/env/approle-secret-id-rotation.env",
         "bank_tls": ".novabank/tls/prod/bank-server.p12", "shop_tls": ".novabank/tls/prod/shop-server.p12",
         "ca": ".novabank/tls/vault/vault.crt", "truststore": ".novabank/tls/vault/vault-truststore.p12"}
ROLES = {"bank": ("bank-prod", "secret/data/Bank-demo/prod", "BANK_PREVIOUS_SECRET_ID_ACCESSOR"),
         "shop": ("shop-prod", "secret/data/shop-app/prod", "SHOP_PREVIOUS_SECRET_ID_ACCESSOR")}
PARAMETERS = {
    "vault-ca-diagnostics": {},
    "vault-status": {}, "vault-unseal": {"unsealKey": True},
    "vault-setup": {"vaultToken": True},
    **{f"vault-{verb}-{target}": {"vaultToken": True, **({"restarted": True} if verb == "finalize" else {})}
       for verb in ("rotate", "finalize") for target in ("bank", "shop", "all")},
    "prod-bootstrap": {"truststorePassword": False, "bankRoleId": False, "bankSecretId": False,
                       "shopRoleId": False, "shopSecretId": False},
    "prod-prepare": {}, "prod-secret-map": {},
    "prod-tls-create": {"bankPassword": True, "shopPassword": True},
    "prod-tls-renew": {"bankPassword": True, "shopPassword": True},
}


def validate_parameters(action, parameters):
    fields = PARAMETERS[action]
    parameters = {} if parameters is None else parameters
    if not isinstance(parameters, dict) or set(parameters) - set(fields):
        raise ValueError("Niedozwolone pola operacji PROD.")
    result = {}
    for key, required in fields.items():
        value = parameters.get(key, False if key == "restarted" else "")
        if key == "restarted":
            if value is not True:
                raise ValueError("Finalizacja wymaga potwierdzenia restartu i poprawnego działania aplikacji PROD.")
        elif not isinstance(value, str) or len(value) > 1024 or (value and not re.fullmatch(r"[A-Za-z0-9._~+/=@!%:-]+", value)) or (required and not value):
            raise ValueError(f"Pole {key}: wymagany pojedynczy sekret/identyfikator bez spacji i poleceń shell.")
        elif "Password" in key and value and len(value) < (6 if key == "truststorePassword" else 16):
            raise ValueError(f"Pole {key}: minimum {6 if key == 'truststorePassword' else 16} znaków.")
        result[key] = value
    return result


class ProductionFiles(ConfigurationFiles):
    def target(self, file):
        if file not in FILES:
            raise ValueError("Plik poza stałą listą PROD.")
        path = self.root / FILES[file]
        for node in (path, *path.parents):
            if node == self.root:
                break
            if node.is_symlink() or (hasattr(node, "is_junction") and node.is_junction()):
                raise ValueError("Dowiązania PROD są niedozwolone.")
        if not path.resolve().is_relative_to(self.root) or (path.exists() and (not path.is_file() or path.stat().st_nlink != 1)):
            raise ValueError("Wymagany zwykły plik PROD w projekcie, bez hardlink.")
        return path

    def snapshot(self):
        result = {}
        for key in FILES:
            path = self.target(key)
            if path.exists() and path.stat().st_size > 262144:
                raise ValueError("Plik PROD przekracza 256 KB.")
            result[key] = path.read_bytes() if path.exists() else None
        return result


class VaultFailure(RuntimeError):
    def __init__(self, code):
        self.code = code
        super().__init__(f"Vault: HTTP {code}. Sprawdź stan, uprawnienia operatora i audyt Vault. Treść odpowiedzi ukryto.")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class VaultClient:
    def __init__(self, ca):
        try:
            context = ssl.create_default_context(cafile=str(ca))
            self.opener = build_opener(ProxyHandler({}), NoRedirect(), HTTPSHandler(context=context))
        except (OSError, ssl.SSLError):
            raise RuntimeError("Brak poprawnego CA Vault: .novabank/tls/vault/vault.crt. Nie wyłączaj weryfikacji TLS.") from None

    def call(self, method, path, payload=None, token=""):
        allowed = path in {"sys/seal-status", "sys/unseal", "sys/auth", "sys/auth/approle", "auth/token/lookup-self", "auth/token/revoke-self", "auth/approle/login"}
        allowed |= any(path == f"sys/policies/acl/{role}" or path == secret_path or path == f"auth/approle/role/{role}" or path in
                       {f"auth/approle/role/{role}/{suffix}" for suffix in ("role-id", "secret-id", "secret-id/lookup", "secret-id-accessor/lookup", "secret-id-accessor/destroy")}
                       for role, secret_path, _ in ROLES.values())
        if not allowed or method not in ("GET", "POST"):
            raise ValueError("Ścieżka/metoda Vault spoza allowlisty.")
        headers = {"Content-Type": "application/json"}
        if token:
            headers["X-Vault-Token"] = token
        request = Request(f"{ADDRESS}/v1/{path}", data=None if payload is None else json.dumps(payload).encode(), headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=8) as response:
                if response.geturl() != request.full_url:
                    raise RuntimeError("Przekierowanie Vault jest niedozwolone.")
                raw = response.read(262145)
                if len(raw) > 262144:
                    raise RuntimeError("Odpowiedź Vault jest za duża.")
                result = json.loads(raw) if raw else {}
                if not isinstance(result, dict):
                    raise RuntimeError("Vault nie zwrócił obiektu JSON.")
                return result
        except HTTPError as exc:
            code = exc.code
            exc.close()
            raise VaultFailure(code) from None
        except (URLError, OSError, json.JSONDecodeError, UnicodeError):
            raise RuntimeError("Nie można połączyć się z lokalnym Vault przez zweryfikowany TLS albo odpowiedź nie jest poprawnym JSON. Szczegóły w audycie Vault.") from None


class ProductionOperations:
    def __init__(self, root, key="production-file-revision", lock=None):
        self.files = ProductionFiles(root, key, lock)

    def client(self):
        return VaultClient(self.files.target("ca"))

    @staticmethod
    def status_text(status):
        if type(status.get("sealed")) is not bool:
            raise RuntimeError("Nieprawidłowy status Vault.")
        numbers = [status.get(key, 0) for key in ("progress", "t", "n")]
        if any(type(value) is not int or not 0 <= value <= 1000 for value in numbers):
            raise RuntimeError("Nieprawidłowe liczniki Vault.")
        return f"Vault: {'ZAPIECZĘTOWANY' if status['sealed'] else 'ODPIECZĘTOWANY'}. Postęp: {numbers[0]}/{numbers[1]}, udziały: {numbers[2]}.\n"

    @staticmethod
    def checked(value):
        if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9._~+/=@!%:-]{1,1024}", value):
            raise RuntimeError("Vault zwrócił nieprawidłowy identyfikator/sekret. Wartość ukryto.")
        return value

    def test_role(self, client, role_id, secret_id, path):
        response = client.call("POST", "auth/approle/login", {"role_id": role_id, "secret_id": secret_id})
        token = self.checked(response.get("auth", {}).get("client_token"))
        try:
            client.call("GET", path, token=token)
        finally:
            client.call("POST", "auth/token/revoke-self", {}, token=token)

    def run(self, action, parameters, emit):
        parameters = validate_parameters(action, parameters)
        if action == "vault-ca-diagnostics":
            from vault_diagnostics import diagnose
            diagnose(self.files.root, emit)
            return
        if action == "prod-secret-map":
            for app, (_, path, _) in ROLES.items():
                emit(f"{app}: bootstrap {FILES[app]}, dane biznesowe Vault {path}, TLS {FILES[app + '_tls']}.\n")
            emit("Udziały unseal i token operatora: poza projektem. Żadnych plików sekretów nie odczytano.\n")
            return
        with self.files.lock:
            original = self.files.snapshot()
            if action == "prod-bootstrap":
                new = {}
                for app, (role, _, _) in ROLES.items():
                    values = assignments(original[app] or b"")
                    if values.get("VAULT_ADDR", ADDRESS) != ADDRESS or values.get("VAULT_ROLE", role) != role:
                        raise ValueError("Istniejący bootstrap wskazuje inny Vault/AppRole. Nie nadpisano konfiguracji; panel obsługuje wyłącznie lokalne role bank-prod/shop-prod.")
                    settings = {"VAULT_ADDR": ADDRESS, "VAULT_ROLE": role, "VAULT_APPROLE_PATH": "approle",
                                "VAULT_SSL_TRUST_STORE": "file:.novabank/tls/vault/vault-truststore.p12", "VAULT_SSL_TRUST_STORE_TYPE": "PKCS12"}
                    for field, property_name in (("truststorePassword", "VAULT_SSL_TRUST_STORE_PASSWORD"), (app + "RoleId", "VAULT_ROLE_ID"), (app + "SecretId", "VAULT_SECRET_ID")):
                        if parameters[field] and not values.get(property_name):
                            settings[property_name] = parameters[field]
                        elif parameters[field] and parameters[field] != values.get(property_name):
                            raise ValueError("Bootstrap zachowuje istniejące poświadczenia. Rotuj Secret ID oddzielną operacją Vault.")
                    new[app] = upsert(original[app] or b"# Bootstrap lokalnego PROD-like. Nie commituj.\n", settings)
                self.files.commit(original, new)
                emit("Przygotowano prod.bank.env i prod.shop.env bez nadpisywania istniejących poświadczeń. Brakujące Role ID/Secret ID uzyskasz przez rotację AppRole. Zapis nie tworzy CA/truststore, danych KV ani baz.\n")
                return
            if action.startswith("prod-tls-"):
                return self.tls(action, parameters, original, emit)
            client = self.client()
            if action in ("vault-status", "vault-unseal"):
                status = client.call("GET", "sys/seal-status")
                if action == "vault-unseal" and status.get("sealed"):
                    if status.get("type") != "shamir":
                        raise ValueError("Panel obsługuje udziały Shamir, nie migrację seal ani klucze recovery auto-unseal.")
                    status = client.call("POST", "sys/unseal", {"key": parameters["unsealKey"]})
                emit(self.status_text(status))
                emit("Udział unseal nie jest przechowywany ani wyświetlany. W razie potrzeby powtórz z innym udziałem.\n" if action == "vault-unseal" else "")
                return
            if action == "prod-prepare":
                missing = []
                for app in ROLES:
                    values = assignments(original[app] or b"")
                    for key in ("VAULT_ADDR", "VAULT_ROLE", "VAULT_ROLE_ID", "VAULT_SECRET_ID", "VAULT_SSL_TRUST_STORE", "VAULT_SSL_TRUST_STORE_PASSWORD"):
                        if not values.get(key):
                            missing.append(f"{app}: {key}")
                    if values.get("VAULT_ADDR") != ADDRESS:
                        missing.append(f"{app}: wymagany lokalny VAULT_ADDR")
                for key in ("bank_tls", "shop_tls", "ca", "truststore"):
                    if not original[key]:
                        missing.append(FILES[key])
                status = client.call("GET", "sys/seal-status")
                emit(self.status_text(status))
                if status.get("sealed"):
                    missing.append("Vault wymaga odpieczętowania")
                if missing:
                    emit("Brakujące elementy: " + ", ".join(missing) + "\n")
                    raise RuntimeError("Przygotowanie PROD niekompletne. Nie wyświetlono wartości sekretów.")
                emit("Pliki/bootstrap i lokalny Vault sprawdzone. Nie potwierdzono połączeń baz, startu aplikacji ani poprawności hasła truststore.\n")
                return
            token = parameters["vaultToken"]
            status = client.call("GET", "sys/seal-status")
            if status.get("sealed") is not False:
                raise RuntimeError("Vault jest zapieczętowany; najpierw wykonaj unseal.")
            info = client.call("GET", "auth/token/lookup-self", token=token).get("data", {})
            if not isinstance(info, dict) or not isinstance(info.get("policies"), list) or not info["policies"]:
                raise RuntimeError("Nie potwierdzono polityk tokenu operatora.")
            if "root" in info.get("policies", []):
                raise ValueError("Token root jest niedozwolony w panelu. Użyj krótkotrwałego tokenu operatora z ograniczoną polityką.")
            if action == "vault-setup":
                mounts = client.call("GET", "sys/auth", token=token).get("data", {})
                if "approle/" not in mounts:
                    client.call("POST", "sys/auth/approle", {"type": "approle"}, token)
                elif mounts["approle/"].get("type") != "approle":
                    raise RuntimeError("Ścieżka approle zajęta przez inny mechanizm. Nie zmieniono jej.")
                for app, (role, secret_path, _) in ROLES.items():
                    policy = f'path "{secret_path}" {{ capabilities = ["read"] }}\npath "sys/internal/ui/mounts/secret" {{ capabilities = ["read"] }}\n'
                    client.call("POST", f"sys/policies/acl/{role}", {"policy": policy}, token)
                    client.call("POST", f"auth/approle/role/{role}", {"token_type": "service", "token_policies": role, "token_ttl": "1h", "token_max_ttl": "24h", "secret_id_ttl": "24h", "secret_id_num_uses": 5}, token)
                    emit(f"Skonfigurowano politykę tylko odczytu i rolę {role}. Nie tworzono biznesowych sekretów KV.\n")
                return
            verb, target = action.removeprefix("vault-").split("-")
            apps = list(ROLES) if target == "all" else [target]
            state = assignments(original["rotation"] or b"")
            if verb == "rotate" and any(state.get(ROLES[app][2]) for app in apps):
                raise ValueError("Istnieje niezakończona rotacja. Zrestartuj aplikacje i wykonaj finalizację przed kolejną rotacją.")
            for app in apps:
                role, path, state_key = ROLES[app]
                values = assignments(original[app] or b"")
                if values.get("VAULT_ADDR", ADDRESS) != ADDRESS:
                    raise ValueError("Bootstrap wskazuje inny Vault. Panel obsługuje wyłącznie lokalny HTTPS 127.0.0.1:8200.")
                if verb == "finalize":
                    accessor = state.get(state_key)
                    if not accessor:
                        emit(f"{role}: brak poprzedniego Secret ID do finalizacji.\n")
                        continue
                    self.test_role(client, self.checked(values.get("VAULT_ROLE_ID")), self.checked(values.get("VAULT_SECRET_ID")), path)
                    # A denied lookup is not proof that the old ID has expired.
                    try:
                        client.call("POST", f"auth/approle/role/{role}/secret-id-accessor/lookup", {"secret_id_accessor": accessor}, token)
                        client.call("POST", f"auth/approle/role/{role}/secret-id-accessor/destroy", {"secret_id_accessor": accessor}, token)
                    except VaultFailure as exc:
                        if exc.code != 404:
                            raise
                    raw = original["rotation"] or b""
                    new_state = b"".join(line for line in raw.splitlines(keepends=True) if line.decode("utf-8-sig").partition("=")[0].strip() != state_key)
                    self.files.commit(original, {"rotation": new_state})
                    original = self.files.snapshot()
                    emit(f"{role}: finalizacja zakończona, stary Secret ID unieważniony albo wcześniej wygasł.\n")
                    continue
                role_id = self.checked(client.call("GET", f"auth/approle/role/{role}/role-id", token=token).get("data", {}).get("role_id"))
                old_accessor = ""
                if values.get("VAULT_SECRET_ID"):
                    try:
                        lookup = client.call("POST", f"auth/approle/role/{role}/secret-id/lookup", {"secret_id": values["VAULT_SECRET_ID"]}, token)
                        old_accessor = self.checked(lookup.get("data", {}).get("secret_id_accessor")) if lookup else ""
                    except VaultFailure as exc:
                        if exc.code != 404:
                            raise
                generation = client.call("POST", f"auth/approle/role/{role}/secret-id", {}, token).get("data", {})
                new_accessor = self.checked(generation.get("secret_id_accessor"))
                try:
                    new_secret = self.checked(generation.get("secret_id"))
                    self.test_role(client, role_id, new_secret, path)
                    new_bootstrap = upsert(original[app] or b"# Bootstrap PROD-like; uzupelnij truststore.\n", {"VAULT_ADDR": ADDRESS, "VAULT_ROLE": role, "VAULT_ROLE_ID": role_id, "VAULT_SECRET_ID": new_secret, "VAULT_APPROLE_PATH": "approle"})
                    updates = {app: new_bootstrap}
                    if old_accessor and old_accessor != new_accessor:
                        updates["rotation"] = upsert(original["rotation"] or b"", {state_key: old_accessor})
                    self.files.commit(original, updates)
                except Exception:
                    try:
                        committed_secret = assignments(self.files.snapshot()[app] or b"").get("VAULT_SECRET_ID") == generation.get("secret_id")
                    except Exception:
                        committed_secret = True  # Unknown local state: do not revoke a possibly installed ID.
                    if committed_secret:
                        emit(f"WARN: {role}: nie potwierdzono wycofania bootstrap. Nowego ID nie unieważniono; sprawdź pliki .control-config-* i audyt Vault przed ponowieniem.\n")
                        raise
                    try:
                        client.call("POST", f"auth/approle/role/{role}/secret-id-accessor/destroy", {"secret_id_accessor": new_accessor}, token)
                    except Exception:
                        emit(f"WARN: {role}: nie potwierdzono usunięcia nowego ID po błędzie. Sprawdź audyt Vault przed ponowieniem.\n")
                    raise
                original = self.files.snapshot()
                emit(f"{role}: nowy Secret ID przetestowany i zapisany w {FILES[app]}. Zrestartuj aplikację; finalizacja unieważni poprzedni ID.\n")
            emit("Operacja dotyczy Secret ID AppRole, nie kluczy API/TOTP ani udziałów unseal. Dla obu aplikacji kroki są osobne; wcześniejszy sukces nie jest cofany po błędzie kolejnej.\n")

    def tls(self, action, parameters, original, emit):
        if action == "prod-tls-create" and any(original[key] for key in ("bank_tls", "shop_tls")):
            raise ValueError("Keystore już istnieje. Użyj jawnej operacji odnowienia z ostrzeżeniem PROD.")
        keytool = shutil.which("keytool")
        if not keytool:
            raise RuntimeError("Brak keytool w środowisku panelu. Uruchom panel w WSL z JDK 25.")
        directory = self.files.target("bank_tls").parent
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        new = {}
        with tempfile.TemporaryDirectory(prefix=".panel-tls-", dir=directory) as temporary:
            for app in ROLES:
                path = Path(temporary) / (app + ".p12")
                environment = {key: value for key, value in os.environ.items() if key in ("PATH", "JAVA_HOME", "HOME", "LANG", "LC_ALL", "TMPDIR", "TEMP", "TMP", "SystemRoot", "SYSTEMROOT", "COMSPEC")}
                environment["NOVABANK_STORE_PASSWORD"] = parameters[app + "Password"]
                command = [keytool, "-genkeypair", "-noprompt", "-alias", app + "-local", "-keyalg", "RSA", "-keysize", "3072", "-validity", "825", "-storetype", "PKCS12", "-keystore", str(path), "-storepass:env", "NOVABANK_STORE_PASSWORD", "-keypass:env", "NOVABANK_STORE_PASSWORD", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1"]
                result = subprocess.run(command, cwd=temporary, env=environment, stdin=subprocess.DEVNULL, capture_output=True, timeout=120, check=False,
                                        **({"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}))
                if result.returncode:
                    raise RuntimeError("Keytool nie wygenerował keystore. Wyjście ukryto, by nie ujawnić poświadczeń. Sprawdź JDK i format hasła.")
                new[app + "_tls"] = path.read_bytes()
                if len(new[app + "_tls"]) > 262144:
                    raise RuntimeError("Keystore przekracza limit pliku.")
            # Recoverable old certificates, not destructive deletion as in the CLI -Force.
            if action == "prod-tls-renew":
                for app in ROLES:
                    key = app + "_tls"
                    if original[key]:
                        backup = self.files.target(key).with_name(app + "-server.p12.previous")
                        if backup.exists() or backup.is_symlink():
                            raise ValueError("Istnieje kopia .previous. Zabezpiecz ją poza projektem przed kolejnym odnowieniem.")
                        with backup.open("xb") as stream:
                            backup.chmod(0o600)
                            stream.write(original[key])
            self.files.commit(original, new)
        emit("Wygenerowano dwa lokalne self-signed keystore PKCS12. Hasła nie są w argumentach, historii ani logach. Uzupełnij właściwe hasła SERVER_SSL_KEY_STORE_PASSWORD w Vault i zrestartuj aplikacje; panel nie zmienił biznesowych sekretów KV ani truststore.\n")
