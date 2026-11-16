"""Bounded, read-only account views in the verified local DEV PostgreSQL container."""
import json
import re

PAGE_SIZE = 50
STATUSES = ("ALL", "ACTIVE", "BLOCKED", "CLOSED")


def account_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[1-9][0-9]{0,18}", value) or int(value) > 9223372036854775807:
        raise ValueError("Nieprawidłowy identyfikator rachunku.")
    return value


def filters(query, detail=False):
    allowed = {"profile"} if detail else {"profile", "q", "status", "currency", "before"}
    if not isinstance(query, dict) or set(query) - allowed or query.get("profile") != ["dev"] or any(not isinstance(v, list) or len(v) != 1 or not isinstance(v[0], str) for v in query.values()):
        raise ValueError("Podgląd rachunków wymaga profilu DEV i pojedynczych dozwolonych parametrów.")
    term = query.get("q", [""])[0]
    if len(term) > 128 or len(term.encode("utf-8")) > 512 or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in term):
        raise ValueError("Szukana fraza: maksymalnie 128 znaków, bez znaków sterujących.")
    status, currency = query.get("status", ["ALL"])[0], query.get("currency", ["ALL"])[0]
    if status not in STATUSES or not re.fullmatch(r"ALL|[A-Z]{3}", currency):
        raise ValueError("Nieprawidłowy status lub kod waluty.")
    before = account_id(query["before"][0]) if "before" in query else None
    return {"q": term.strip(), "status": status, "currency": currency, "before": before}


def read_query(select):
    return "BEGIN READ ONLY;\nSET LOCAL statement_timeout = '5s';\nSET LOCAL lock_timeout = '1s';\n" + select + "\nCOMMIT;\n"


def listing_sql(values):
    # Revalidate even for internal callers; arbitrary strings never become SQL syntax.
    values = filters({"profile": ["dev"], **{key: [value] for key, value in values.items() if value is not None}})
    term = values["q"].encode("utf-8").hex()
    conditions = []
    if values["status"] != "ALL":
        conditions.append("a.status = '" + values["status"] + "'")
    if values["currency"] != "ALL":
        conditions.append("a.currency = '" + values["currency"] + "'")
    if values["before"]:
        conditions.append("a.id < " + values["before"])
    where = " AND ".join(["true", *conditions])
    return read_query(f"""WITH search AS (
        SELECT lower(convert_from(decode('{term}', 'hex'), 'UTF8')) AS term,
            regexp_replace(regexp_replace(upper(convert_from(decode('{term}', 'hex'), 'UTF8')), '[[:space:]]', '', 'g'), '^PL', '') AS number_term
    )
    SELECT COALESCE(json_agg(row_to_json(page)), '[]'::json) FROM (
        SELECT a.id::text AS id, u.id::text AS "ownerId",
            u.first_name || ' ' || u.last_name AS owner, u.email,
            '•••• ' || RIGHT(a.account_number, 4) AS number,
            a.balance::text AS balance, a.currency, a.status, u.status AS "userStatus"
        FROM accounts a JOIN users u ON u.id = a.owner_id CROSS JOIN search s
        WHERE {where} AND (s.term = '' OR strpos(lower(u.first_name || ' ' || u.last_name), s.term) > 0
            OR strpos(lower(u.email), s.term) > 0 OR a.id::text = s.term OR u.id::text = s.term
            OR (s.number_term ~ '^[0-9]+$' AND strpos(a.account_number, s.number_term) > 0))
        ORDER BY a.id DESC LIMIT {PAGE_SIZE + 1}
    ) page;""")


def detail_sql(identifier):
    identifier = account_id(identifier)
    return read_query(f"""SELECT row_to_json(detail) FROM (
        SELECT a.id::text AS id, u.id::text AS "ownerId", a.account_number AS "accountNumber",
            a.balance::text AS balance, a.currency, a.status, a.created_at AS "createdAt", a.updated_at AS "updatedAt",
            u.first_name || ' ' || u.last_name AS owner, u.email, u.status AS "userStatus", u.role,
            u.email_verified AS "emailVerified", u.totp_enabled AS "twoFactorEnabled",
            u.created_at AS "userCreatedAt", u.last_login_at AS "lastLoginAt"
        FROM accounts a JOIN users u ON u.id = a.owner_id WHERE a.id = {identifier}
    ) detail;""")


class DevAccounts:
    def __init__(self, database):
        self.database = database

    def read(self, sql):
        try:
            # Reuse the DEV socket/Compose checkout identity checks, not an arbitrary DSN.
            raw = self.database._run(self.database._base_command(), sql=sql).strip()
            return json.loads(raw) if raw else None
        except (OSError, RuntimeError, ValueError):
            raise RuntimeError("Nie można odczytać rachunków lokalnej bazy DEV. Sprawdź WSL, Docker, konfigurację i migracje banku.") from None

    def listing(self, values):
        rows = self.read(listing_sql(values))
        if not isinstance(rows, list) or len(rows) > PAGE_SIZE + 1:
            raise RuntimeError("Nieprawidłowy wynik odczytu rachunków DEV.")
        return {"accounts": rows[:PAGE_SIZE], "next": rows[PAGE_SIZE - 1]["id"] if len(rows) > PAGE_SIZE else None,
                "pageSize": PAGE_SIZE, "readOnly": True}

    def detail(self, identifier):
        item = self.read(detail_sql(identifier))
        if item is None:
            raise LookupError("Nie znaleziono rachunku DEV.")
        if not isinstance(item, dict):
            raise RuntimeError("Nieprawidłowy wynik odczytu rachunku DEV.")
        return {"account": item, "readOnly": True}
