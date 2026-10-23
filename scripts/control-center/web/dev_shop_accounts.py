"""Read-only shop users, restricted to this checkout's local DEV Compose database."""
import json
import os
import re
import shutil

from dev_accounts import PAGE_SIZE, account_id, read_query
from dev_cash import DevCash, normalized_path


def filters(query, detail=False):
    allowed = {"profile"} if detail else {"profile", "q", "status", "role", "before"}
    if not isinstance(query, dict) or set(query) - allowed or query.get("profile") != ["dev"] or any(not isinstance(v, list) or len(v) != 1 or not isinstance(v[0], str) for v in query.values()):
        raise ValueError("Podgląd kont sklepu wymaga pojedynczych dozwolonych parametrów i profilu DEV.")
    term = query.get("q", [""])[0]
    if len(term) > 128 or len(term.encode("utf-8")) > 512 or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in term):
        raise ValueError("Szukana fraza: maksymalnie 128 znaków, bez znaków sterujących.")
    status, role = query.get("status", ["ALL"])[0], query.get("role", ["ALL"])[0]
    if status not in ("ALL", "ACTIVE", "INACTIVE") or role not in ("ALL", "CUSTOMER", "ADMIN"):
        raise ValueError("Nieprawidłowa aktywność lub rola konta sklepu.")
    return {"q": term.strip(), "status": status, "role": role,
            "before": account_id(query["before"][0]) if "before" in query else None}


def listing_sql(values):
    values = filters({"profile": ["dev"], **{key: [value] for key, value in values.items() if value is not None}})
    term = values["q"].encode("utf-8").hex()
    conditions = ["true"]
    if values["status"] != "ALL":
        conditions.append("u.active = " + ("true" if values["status"] == "ACTIVE" else "false"))
    if values["role"] != "ALL":
        conditions.append("u.role = '" + values["role"] + "'")
    if values["before"]:
        conditions.append("u.id < " + values["before"])
    return read_query(f"""WITH search AS (
        SELECT lower(convert_from(decode('{term}', 'hex'), 'UTF8')) AS term
    ) SELECT COALESCE(json_agg(row_to_json(page)), '[]'::json) FROM (
        SELECT u.id::text AS id, u.customer_id AS "customerId", u.email,
            u.first_name AS "firstName", u.last_name AS "lastName", u.active, u.role,
            u.created_at AS "createdAt"
        FROM shop_users u CROSS JOIN search s
        WHERE {' AND '.join(conditions)} AND (s.term = '' OR strpos(lower(u.email), s.term) > 0
            OR strpos(lower(u.customer_id), s.term) > 0
            OR strpos(lower(concat_ws(' ', u.first_name, u.last_name)), s.term) > 0 OR u.id::text = s.term)
        ORDER BY u.id DESC LIMIT {PAGE_SIZE + 1}
    ) page;""")


def detail_sql(identifier):
    identifier = account_id(identifier)
    return read_query(f"""SELECT row_to_json(detail) FROM (
        SELECT u.id::text AS id, u.customer_id AS "customerId", u.email,
            u.first_name AS "firstName", u.last_name AS "lastName", u.active, u.role,
            u.created_at AS "createdAt", u.updated_at AS "updatedAt",
            COALESCE(length(btrim(u.phone)) > 0, false) AS "hasPhone",
            EXISTS (SELECT 1 FROM shop_user_addresses a WHERE a.user_id = u.id) AS "hasAddress"
        FROM shop_users u WHERE u.id = {identifier}
    ) detail;""")


class DevShopDatabase(DevCash):
    # Reuse only the bounded subprocess runner. No cash-adjustment route uses this class.
    def _base_command(self):
        if os.name == "nt":
            raise RuntimeError("Podgląd sklepu wymaga WSL/Bash.")
        docker = shutil.which("docker")
        if not docker or self.settings.get("SHOP_POSTGRES_HOST", "localhost") not in ("localhost", "127.0.0.1", "::1"):
            raise RuntimeError("Wymagany lokalny Docker i baza sklepu DEV.")
        command = [docker, "--host", "unix:///var/run/docker.sock"]
        metadata = json.loads(self._run(command + ["inspect", "shop-postgres", "--format",
            '{"labels":{{json .Config.Labels}},"running":{{json .State.Running}}}']))
        if not isinstance(metadata, dict) or not isinstance(metadata.get("labels"), dict):
            raise RuntimeError("Nie potwierdzono lokalnej bazy sklepu DEV.")
        labels = metadata["labels"]
        if (not metadata.get("running") or labels.get("com.docker.compose.project") != "bank-demo"
                or labels.get("com.docker.compose.service") != "shop-postgres"
                or normalized_path(labels.get("com.docker.compose.project.config_files", "")) != normalized_path(self.root / "docker-compose.yaml")
                or normalized_path(labels.get("com.docker.compose.project.working_dir", "")) != normalized_path(self.root)):
            raise RuntimeError("Kontener nie jest bazą sklepu DEV tego checkoutu.")
        user, database = self.settings.get("SHOP_POSTGRES_USER", "shopuser"), self.settings.get("SHOP_POSTGRES_DB", "shopdb")
        if not all(re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,62}", value) for value in (user, database)):
            raise ValueError("Nieprawidłowa nazwa bazy/użytkownika sklepu DEV.")
        return command + ["exec", "-i", "shop-postgres", "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", database]


class DevShopAccounts:
    def __init__(self, database):
        self.database = database

    def read(self, sql):
        try:
            raw = self.database._run(self.database._base_command(), sql=sql).strip()
            return json.loads(raw) if raw else None
        except (OSError, RuntimeError, ValueError):
            raise RuntimeError("Nie można odczytać kont sklepu DEV. Sprawdź WSL, Docker, konfigurację i migracje sklepu.") from None

    def listing(self, values):
        rows = self.read(listing_sql(values))
        if not isinstance(rows, list) or len(rows) > PAGE_SIZE + 1:
            raise RuntimeError("Nieprawidłowy wynik odczytu kont sklepu DEV.")
        return {"accounts": rows[:PAGE_SIZE], "next": rows[PAGE_SIZE - 1]["id"] if len(rows) > PAGE_SIZE else None,
                "pageSize": PAGE_SIZE, "readOnly": True}

    def detail(self, identifier):
        item = self.read(detail_sql(identifier))
        if item is None:
            raise LookupError("Nie znaleziono konta sklepu DEV.")
        if not isinstance(item, dict):
            raise RuntimeError("Nieprawidłowy wynik odczytu konta sklepu DEV.")
        return {"account": item, "readOnly": True}
