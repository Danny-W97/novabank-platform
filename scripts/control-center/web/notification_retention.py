"""Explicit, export-first retention of closed local notifications; no automatic purge."""
from datetime import datetime, timedelta, timezone
import hashlib
import json
import secrets
import threading


class NotificationRetention:
    def __init__(self, notifications):
        self.notifications = notifications
        self.plans = {}
        self.lock = threading.RLock()

    @staticmethod
    def validate(profile, trash_days, archive_days):
        if profile not in ("dev", "test"):
            raise ValueError("Retencja powiadomień jest dostępna tylko w DEV/TEST.")
        if any(type(value) is not int or not 1 <= value <= 3650 for value in (trash_days, archive_days)):
            raise ValueError("Okresy retencji muszą mieścić się w zakresie 1–3650 dni.")

    def eligible(self, database, trash_cutoff, archive_cutoff, profile):
        return [dict(row) for row in database.execute("""
            SELECT * FROM notifications n
            WHERE ((state='deleted' AND state_changed_at<=?) OR (state='archived' AND state_changed_at<=?))
              AND COALESCE(profile,'dev')=?
              AND (issue_code IS NULL OR outcome<>'open')
              AND NOT EXISTS (SELECT 1 FROM repair_requests r WHERE r.notification_id=n.id AND r.finished IS NULL)
            ORDER BY id LIMIT 1000
            """, (trash_cutoff, archive_cutoff, profile))]

    def plan(self, profile, trash_days=30, archive_days=180):
        self.validate(profile, trash_days, archive_days)
        now = datetime.now(timezone.utc)
        cutoffs = ((now-timedelta(days=trash_days)).isoformat(), (now-timedelta(days=archive_days)).isoformat())
        with self.lock, self.notifications.lock, self.notifications.connect() as database:
            rows = self.eligible(database, *cutoffs, profile)
            self.plans = {key: value for key, value in self.plans.items() if value["expires"] > now}
            if len(self.plans) >= 8:
                raise ValueError("Otworzono zbyt wiele planów; poczekaj pięć minut.")
            ident = secrets.token_hex(16)
            self.plans[ident] = {"profile": profile, "cutoffs": cutoffs, "rows": rows,
                                 "expires": now+timedelta(minutes=5), "exported": False}
            return {"planId": ident, "count": len(rows), "batchLimit": 1000,
                    "trashDays": trash_days, "archiveDays": archive_days,
                    "databaseBytes": self.notifications.database.stat().st_size,
                    "counts": self.notifications.counts(database),
                    "note": "Bez wpisów aktywnych, nierozwiązanych problemów, PROD i trwających napraw. "
                            "Plan wygasa po 5 minutach. Najpierw pobierz eksport; usunięcia nie można cofnąć."}

    def get(self, ident, profile):
        plan = self.plans.get(ident) if isinstance(ident, str) else None
        if not plan or plan["profile"] != profile or plan["expires"] <= datetime.now(timezone.utc):
            raise ValueError("Plan wygasł lub należy do innego profilu. Utwórz nowy podgląd.")
        return plan

    def export(self, ident, profile):
        with self.lock:
            plan = self.get(ident, profile)
            content = json.dumps({"format": 1, "planId": ident, "notifications": plan["rows"]},
                                 ensure_ascii=False, indent=2)
            digest = hashlib.sha256(content.encode()).hexdigest()
            plan["exported"] = True
            return {"filename": "notifications-"+ident+".json", "content": content, "sha256": digest}

    def purge(self, ident, profile, confirmed=False, acknowledgement=""):
        if confirmed is not True or acknowledgement != "USUN":
            raise ValueError("Trwałe usunięcie wymaga potwierdzenia i wpisania USUN.")
        with self.lock, self.notifications.lock, self.notifications.connect() as database:
            plan = self.get(ident, profile)
            if not plan["exported"]:
                raise ValueError("Najpierw pobierz eksport powiadomień.")
            database.execute("BEGIN IMMEDIATE")
            current = {row["id"]: row for row in self.eligible(database, *plan["cutoffs"], profile)}
            if any(current.get(row["id"]) != row for row in plan["rows"]):
                raise ValueError("Powiadomienia zmieniły się od podglądu; niczego nie usunięto. Utwórz nowy plan.")
            now = datetime.now(timezone.utc)
            # Compact deduplication metadata expires; original log cursors are retained unchanged.
            database.execute("DELETE FROM notification_tombstones WHERE expires<=?", (now.isoformat(),))
            for row in plan["rows"]:
                database.execute("INSERT OR REPLACE INTO notification_tombstones VALUES (?,?)",
                                 (row["fingerprint"], (now+timedelta(days=365)).isoformat()))
                database.execute("DELETE FROM repair_requests WHERE notification_id=? AND finished IS NOT NULL", (row["id"],))
                database.execute("DELETE FROM notifications WHERE id=?", (row["id"],))
            database.execute("UPDATE notification_revision SET revision=revision+1 WHERE id=1")
            self.plans.pop(ident)
            return {"removed": len(plan["rows"]), "counts": self.notifications.counts(database),
                    "note": "Usunięto wyłącznie wyeksportowaną partię. Zwolnione strony SQLite są ponownie używane; "
                            "nie wykonywano VACUUM ani usuwania pliku bazy."}

    def action(self, payload):
        if not isinstance(payload, dict):
            raise ValueError("Nieprawidłowe żądanie retencji.")
        operation = payload.get("operation")
        fields = {"plan": {"operation", "profile", "trashDays", "archiveDays"},
                  "export": {"operation", "profile", "planId"},
                  "purge": {"operation", "profile", "planId", "confirmed", "acknowledgement"}}
        if operation not in fields or set(payload) != fields[operation]:
            raise ValueError("Nieprawidłowy zakres żądania retencji.")
        profile = payload["profile"]
        if profile not in ("dev", "test"):
            raise ValueError("Retencja powiadomień jest dostępna tylko w DEV/TEST.")
        if operation == "plan":
            return self.plan(profile, payload["trashDays"], payload["archiveDays"])
        if operation == "export":
            return self.export(payload["planId"], profile)
        return self.purge(payload["planId"], profile, payload["confirmed"], payload["acknowledgement"])
