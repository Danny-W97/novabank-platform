"""Allowlisted, atomic test-money corrections in THIS checkout's local DEV database."""
from decimal import Decimal
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid


def validate_cash(parameters):
    if not isinstance(parameters, dict) or set(parameters) != {"accountId", "amount", "requestId"}:
        raise ValueError("Wymagane konto, kwota i identyfikator korekty.")
    account_id, amount, request_id = (parameters[key] for key in ("accountId", "amount", "requestId"))
    if type(account_id) is not int or not 1 <= account_id <= 9223372036854775807:
        raise ValueError("Nieprawidłowy identyfikator konta.")
    if not isinstance(amount, str) or not re.fullmatch(r"(?:0|[1-9]\d{0,8})(?:\.\d{1,2})?", amount):
        raise ValueError("Kwota: od 0.01 do 999999999.99 PLN, maksymalnie dwa miejsca po przecinku.")
    if Decimal(amount) <= 0:
        raise ValueError("Kwota musi być dodatnia.")
    if not isinstance(request_id, str):
        raise ValueError("Nieprawidłowy identyfikator korekty.")
    try:
        if str(uuid.UUID(request_id)) != request_id:
            raise ValueError()
    except ValueError:
        raise ValueError("Nieprawidłowy identyfikator korekty.") from None
    return {"accountId": account_id, "amount": format(Decimal(amount), ".2f"), "requestId": request_id}


def normalized_path(value):
    value = str(value).replace("\\", "/").rstrip("/")
    if re.match(r"^[A-Za-z]:/", value):
        value = "/mnt/" + value[0].lower() + value[2:]
    return value.casefold()


def adjustment_sql(direction, parameters):
    if direction not in ("CREDIT", "DEBIT"):
        raise ValueError("Nieznany rodzaj korekty.")
    parameters = validate_cash(parameters)
    account_id, amount, request_id = (parameters[key] for key in ("accountId", "amount", "requestId"))
    delta = amount if direction == "CREDIT" else "-" + amount
    # Interpolated literals have strict numeric/UUID/enum validation above. No arbitrary SQL input.
    return f"""
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '10s';
DO $$ BEGIN PERFORM pg_advisory_xact_lock(hashtextextended('dev-cash:{request_id}', 0)); END $$;
WITH previous AS MATERIALIZED (
    SELECT * FROM dev_cash_adjustments WHERE request_id = '{request_id}'::uuid
), locked AS MATERIALIZED (
    SELECT id, balance, currency, status FROM accounts
    WHERE id = {account_id} AND NOT EXISTS (SELECT 1 FROM previous) FOR UPDATE
), changed AS (
    UPDATE accounts a SET balance = locked.balance + ({delta})::numeric,
        version = a.version + 1, updated_at = CURRENT_TIMESTAMP
    FROM locked WHERE a.id = locked.id AND locked.status = 'ACTIVE'
        AND locked.currency = 'PLN' AND locked.balance + ({delta})::numeric >= 0
    RETURNING a.id, a.balance
), recorded AS (
    INSERT INTO dev_cash_adjustments
        (request_id, account_id, direction, amount, currency, balance_before, balance_after)
    SELECT '{request_id}'::uuid, changed.id, '{direction}', {amount}, 'PLN', locked.balance, changed.balance
    FROM changed JOIN locked ON changed.id = locked.id RETURNING *
)
SELECT COALESCE(
    (SELECT json_build_object('ok', true, 'accountId', account_id, 'before', balance_before::text,
        'after', balance_after::text, 'amount', amount::text, 'replayed', false) FROM recorded),
    (SELECT json_build_object('ok', true, 'accountId', account_id, 'before', balance_before::text,
        'after', balance_after::text, 'amount', amount::text, 'replayed', true) FROM previous
        WHERE account_id = {account_id} AND direction = '{direction}' AND amount = {amount}),
    json_build_object('ok', false, 'error', CASE WHEN EXISTS (SELECT 1 FROM previous)
        THEN 'IDEMPOTENCY_CONFLICT' ELSE 'ACCOUNT_OR_BALANCE_UNAVAILABLE' END)
);
COMMIT;
"""


class DevCash:
    def __init__(self, root, settings, environment):
        self.root = Path(root).resolve()
        self.settings = settings
        self.environment = environment

    def _run(self, command, *, sql=None):
        try:
            result = subprocess.run(command, input=sql, text=True, encoding="utf-8", errors="replace",
                                    capture_output=True, timeout=20, cwd=self.root, env=self.environment,
                                    **({"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}))
        except (OSError, subprocess.TimeoutExpired):
            raise RuntimeError("Brak potwierdzenia operacji bazy DEV. Nie ponawiaj korekty z nowym identyfikatorem.") from None
        if result.returncode != 0:
            # Never expose Docker/psql stderr or connection/configuration secrets.
            raise RuntimeError("Operacja lokalnej bazy DEV nie powiodła się. Sprawdź Docker, konfigurację i migrację V25.")
        return result.stdout

    def _base_command(self):
        if os.name == "nt":
            raise RuntimeError("Korekty salda wymagają uruchomienia Control Center w WSL/Bash.")
        docker = shutil.which("docker")
        if not docker:
            raise RuntimeError("Brak Docker w WSL.")
        if self.settings.get("POSTGRES_HOST", "localhost") not in ("localhost", "127.0.0.1", "::1"):
            raise RuntimeError("Korekty salda nie obsługują zdalnych baz.")
        command = [docker, "--host", "unix:///var/run/docker.sock"]
        metadata = json.loads(self._run(command + ["inspect", "bank-postgres", "--format",
            '{"labels":{{json .Config.Labels}},"running":{{json .State.Running}}}']))
        if not isinstance(metadata, dict) or not isinstance(metadata.get("labels"), dict):
            raise RuntimeError("Nie potwierdzono tożsamości lokalnego kontenera DEV.")
        labels = metadata.get("labels") or {}
        expected_file = normalized_path(self.root / "docker-compose.yaml")
        if (not metadata.get("running") or labels.get("com.docker.compose.project") != "bank-demo"
                or labels.get("com.docker.compose.service") != "postgres"
                or normalized_path(labels.get("com.docker.compose.project.config_files", "")) != expected_file
                or normalized_path(labels.get("com.docker.compose.project.working_dir", "")) != normalized_path(self.root)):
            raise RuntimeError("Kontener nie jest lokalną bazą DEV tego repozytorium. Korekta zablokowana.")
        user = self.settings.get("POSTGRES_USER", "bankuser")
        database = self.settings.get("POSTGRES_DB", "bankdb")
        if not all(re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,62}", value) for value in (user, database)):
            raise ValueError("Nieprawidłowa nazwa bazy lub użytkownika DEV.")
        return command + ["exec", "-i", "bank-postgres", "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                          "-U", user, "-d", database]

    def accounts(self):
        sql = """SET statement_timeout = '5s';
            SELECT COALESCE(json_agg(row_to_json(a)), '[]'::json) FROM (
                SELECT a.id, u.first_name || ' ' || u.last_name AS owner,
                    '•••• ' || RIGHT(a.account_number, 4) AS number, a.balance::text AS balance,
                    a.currency, a.status
                FROM accounts a JOIN users u ON u.id = a.owner_id
                WHERE a.status = 'ACTIVE' AND a.currency = 'PLN' ORDER BY a.id LIMIT 501
            ) a;"""
        rows = json.loads(self._run(self._base_command(), sql=sql))
        return {"accounts": rows[:500], "truncated": len(rows) > 500}

    def adjust(self, direction, parameters):
        sql = adjustment_sql(direction, parameters)
        result = json.loads(self._run(self._base_command(), sql=sql))
        if not result.get("ok"):
            if result.get("error") == "IDEMPOTENCY_CONFLICT":
                raise ValueError("Identyfikator korekty był już użyty dla innych danych.")
            raise ValueError("Konto nie istnieje, nie jest aktywne/PLN albo brak środków do odjęcia.")
        return result
