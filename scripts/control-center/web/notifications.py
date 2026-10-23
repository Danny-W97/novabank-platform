"""Durable, paginated, redacted notifications from fixed local DEV logs and panel jobs."""
import hashlib
import gzip
import re
import sqlite3
import json
import threading
from contextlib import contextmanager
from datetime import datetime, timezone

ANSI = re.compile(r"\x1b\[[0-9;]*m")
LEVEL = re.compile(r"(?:^|\s|\[)(WARN(?:ING)?|ERROR)(?:\s|\]|:)|^Błąd:", re.I)
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+(?:[+-]\d{2}:\d{2}|Z)?")


class Notifications:
    def __init__(self, root, runtime, redact):
        self.root = root
        self.runtime = runtime
        self.redact = redact
        self.lock = threading.RLock()
        runtime.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.database = runtime / "notifications.sqlite"
        with self.connect() as database:
            database.executescript("""
                CREATE TABLE IF NOT EXISTS notifications (
                    id INTEGER PRIMARY KEY, fingerprint TEXT NOT NULL UNIQUE,
                    source TEXT NOT NULL, level TEXT NOT NULL, message TEXT NOT NULL, created TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS log_cursors (
                    path TEXT PRIMARY KEY, identity TEXT NOT NULL, offset INTEGER NOT NULL,
                    dropping INTEGER NOT NULL DEFAULT 0, private_key INTEGER NOT NULL DEFAULT 0
                );
            """)
            columns = {row[1] for row in database.execute("PRAGMA table_info(log_cursors)")}
            for name in ("dropping", "private_key"):
                if name not in columns:
                    database.execute(f"ALTER TABLE log_cursors ADD COLUMN {name} INTEGER NOT NULL DEFAULT 0")
            notification_columns = {row[1] for row in database.execute("PRAGMA table_info(notifications)")}
            additions = {
                "state": "TEXT NOT NULL DEFAULT 'active' CHECK (state IN ('active','archived','deleted'))",
                "previous_state": "TEXT CHECK (previous_state IN ('active','archived'))",
                "state_changed_at": "TEXT",
                "issue_code": "TEXT", "profile": "TEXT", "job_id": "TEXT",
                "issue_context": "TEXT NOT NULL DEFAULT '{}'",
                "outcome": "TEXT NOT NULL DEFAULT 'open'", "outcome_at": "TEXT", "outcome_note": "TEXT",
            }
            for name, definition in additions.items():
                if name not in notification_columns:
                    database.execute(f"ALTER TABLE notifications ADD COLUMN {name} {definition}")
            database.executescript("""
                CREATE INDEX IF NOT EXISTS ix_notifications_state_id ON notifications(state, id DESC);
                CREATE TABLE IF NOT EXISTS notification_sequence (id INTEGER PRIMARY KEY CHECK(id=1), last_id INTEGER NOT NULL);
                INSERT OR IGNORE INTO notification_sequence SELECT 1, COALESCE(MAX(id),0) FROM notifications;
                CREATE TABLE IF NOT EXISTS notification_revision (id INTEGER PRIMARY KEY CHECK (id = 1), revision INTEGER NOT NULL);
                INSERT OR IGNORE INTO notification_revision (id, revision) VALUES (1, 1);
                CREATE TABLE IF NOT EXISTS notification_tombstones (fingerprint TEXT PRIMARY KEY, expires TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS ix_notification_tombstones_expiry ON notification_tombstones(expires);
                CREATE TABLE IF NOT EXISTS repair_requests (
                    request_id TEXT PRIMARY KEY, notification_id INTEGER NOT NULL,
                    revision TEXT NOT NULL, job_id TEXT NOT NULL, state TEXT NOT NULL,
                    created TEXT NOT NULL, finished TEXT
                );
            """)
        self.database.chmod(0o600)

    @contextmanager
    def connect(self):
        database = sqlite3.connect(self.database, timeout=10)
        database.row_factory = sqlite3.Row
        try:
            with database:
                yield database
        finally:
            database.close()

    def add(self, source, level, message, *, fingerprint=None, created=None, issue_code=None, profile=None, job_id=None, context=None):
        from problems import CATALOG
        if issue_code is not None and issue_code not in CATALOG:
            raise ValueError("Nieznany kod problemu.")
        if profile not in (None, "dev", "test", "prod") or (job_id is not None and not re.fullmatch(r"[0-9a-f]{32}", job_id)):
            raise ValueError("Nieprawidłowy kontekst problemu.")
        context = dict(context or {})
        if set(context) - {"component", "port"} or context.get("component") not in (None, "bank", "shop"):
            raise ValueError("Niedozwolony kontekst problemu.")
        if "port" in context and (type(context["port"]) is not int or not 1024 <= context["port"] <= 65535):
            raise ValueError("Nieprawidłowy port problemu.")
        message = self.redact(message[:16_000])[:4000]
        created = created or datetime.now(timezone.utc).isoformat(timespec="seconds")
        fingerprint = fingerprint or f"{source}:{level}:{created}:{message}"
        digest = hashlib.sha256(fingerprint.encode("utf-8")).hexdigest()
        with self.lock, self.connect() as database:
            database.execute("BEGIN IMMEDIATE")
            if database.execute("SELECT 1 FROM notification_tombstones WHERE fingerprint = ? AND expires > ?",
                                (digest, datetime.now(timezone.utc).isoformat())).fetchone():
                return None
            database.execute("UPDATE notification_sequence SET last_id=last_id+1 WHERE id=1")
            next_id = database.execute("SELECT last_id FROM notification_sequence WHERE id=1").fetchone()[0]
            inserted = database.execute("INSERT OR IGNORE INTO notifications (id, fingerprint, source, level, message, created, issue_code, profile, job_id, issue_context) "
                                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", (next_id, digest, source, level, message, created, issue_code, profile, job_id, json.dumps(context)))
            if inserted.rowcount:
                database.execute("UPDATE notification_revision SET revision = revision + 1 WHERE id = 1")
            return database.execute("SELECT id FROM notifications WHERE fingerprint = ?", (digest,)).fetchone()[0]

    def ingest_line(self, source, line, *, identity=None):
        line = ANSI.sub("", line).strip()
        severity = LEVEL.search(line[:250])
        if not severity or len(line) > 16_000 or "PRIVATE KEY-----" in line:
            return
        level = "ERROR" if severity.group(1) is None or severity.group(1).upper() == "ERROR" else "WARN"
        timestamp = DATE.match(line)
        created = timestamp.group() if timestamp else None
        # Same Java line captured in console and rolling file becomes ONE notification.
        # Console/file patterns can have different spacing or abbreviated logger names.
        body = line.partition(" : ")[2]
        canonical = f"{created}:{level}:{body}" if timestamp and body else line
        fingerprint = f"{source}:{canonical}" if timestamp else f"{source}:{identity or line}:{line}"
        self.add(source, level, line, fingerprint=fingerprint, created=created)

    def sources(self):
        sources = [
            ("Bank", self.root / ".novabank/logs/bank-dev.log"),
            ("Sklep", self.root / ".novabank/logs/shop-dev.log"),
            ("Sklep", self.root / "shop-app/.novabank/logs/shop-dev.log"),
            ("Bank", self.runtime / "bank-dev.log"),
            ("Sklep", self.runtime / "shop-dev.log"),
        ]
        # Only our explicitly configured rolling DEV logs, never arbitrary directories/files.
        for source, directory, prefix in (("Bank", self.root / ".novabank/logs", "bank-dev"),
                                           ("Sklep", self.root / ".novabank/logs", "shop-dev"),
                                           ("Sklep", self.root / "shop-app/.novabank/logs", "shop-dev")):
            archives = sorted(path for path in directory.glob(prefix + ".*.log.gz")
                              if re.fullmatch(re.escape(prefix) + r"\.\d{4}-\d{2}-\d{2}\.\d+\.log\.gz", path.name))
            sources.extend((source, path) for path in archives)
        return sources

    def scan(self):
        with self.lock:
            for source, path in self.sources():
                if not path.is_file() or path.is_symlink():
                    continue
                # Do not follow parent-directory symlinks outside the checkout.
                if not path.resolve().is_relative_to(self.root.resolve()):
                    continue
                stat = path.stat()
                identity = f"{stat.st_dev}:{stat.st_ino}"
                with self.connect() as database:
                    cursor = database.execute("SELECT * FROM log_cursors WHERE path = ?", (str(path),)).fetchone()
                compressed = path.suffix == ".gz"
                same_file = cursor and cursor["identity"] == identity and (compressed or cursor["offset"] <= stat.st_size)
                offset = cursor["offset"] if same_file else 0
                dropping = bool(cursor["dropping"]) if same_file else False
                private_key = bool(cursor["private_key"]) if same_file else False
                read_bytes = 0
                with (gzip.open(path, "rb") if compressed else path.open("rb")) as stream:
                    stream.seek(offset)
                    while read_bytes < 512_000:
                        start = stream.tell()
                        raw = stream.readline(16_001)
                        if not raw:
                            break
                        if dropping or len(raw) > 16_000:
                            # Persist drop state across bounded scans; never parse an overlong record's tail.
                            dropping = not raw.endswith(b"\n")
                            offset = stream.tell()
                            read_bytes += offset - start
                            continue
                        if not raw.endswith(b"\n"):
                            break  # Writer has not finished the record; retry it on next scan.
                        offset = stream.tell()
                        read_bytes += len(raw)
                        line = raw.decode("utf-8", errors="replace")
                        if re.search(r"-----BEGIN .*PRIVATE KEY-----", line):
                            private_key = True
                        elif private_key:
                            if re.search(r"-----END .*PRIVATE KEY-----", line):
                                private_key = False
                        else:
                            self.ingest_line(source, line, identity=f"{identity}:{start}")
                with self.connect() as database:
                    database.execute("INSERT INTO log_cursors(path, identity, offset, dropping, private_key) VALUES (?, ?, ?, ?, ?) "
                                     "ON CONFLICT(path) DO UPDATE SET identity = excluded.identity, offset = excluded.offset, "
                                     "dropping = excluded.dropping, private_key = excluded.private_key",
                                     (str(path), identity, offset, int(dropping), int(private_key)))

    @staticmethod
    def validate_id(notification_id):
        if type(notification_id) is not int or not 1 <= notification_id <= 9223372036854775807:
            raise ValueError("Nieprawidłowy identyfikator powiadomienia.")

    @staticmethod
    def counts(database):
        result = {"active": 0, "archived": 0, "deleted": 0}
        for row in database.execute("SELECT state, count(*) AS count FROM notifications GROUP BY state"):
            result[row["state"]] = row["count"]
        return result

    @staticmethod
    def find(database, notification_id):
        row = database.execute("SELECT id, source, level, message, created, state, previous_state, state_changed_at "
                               ", issue_code, profile, job_id, issue_context, outcome, outcome_at, outcome_note "
                               "FROM notifications WHERE id = ?", (notification_id,)).fetchone()
        if row is None:
            raise LookupError("Nie znaleziono powiadomienia.")
        item = dict(row)
        item["issue_context"] = json.loads(item["issue_context"])
        return item

    def mark_outcome(self, notification_id, outcome, note):
        if outcome not in ("open", "configuration_verified", "condition_cleared", "manual"):
            raise ValueError("Nieznany wynik diagnostyki.")
        with self.lock, self.connect() as database:
            self.find(database, notification_id)
            database.execute("UPDATE notifications SET outcome = ?, outcome_at = ?, outcome_note = ? WHERE id = ?",
                             (outcome, datetime.now(timezone.utc).isoformat(), self.redact(note)[:1000], notification_id))
            database.execute("UPDATE notification_revision SET revision = revision + 1 WHERE id = 1")

    def reserve_repair(self, request_id, notification_id, revision, job_id):
        with self.lock, self.connect() as database:
            database.execute("BEGIN IMMEDIATE")
            row = database.execute("SELECT * FROM repair_requests WHERE request_id = ?", (request_id,)).fetchone()
            if row:
                if row["notification_id"] != notification_id or row["revision"] != revision:
                    raise ValueError("Identyfikator ponowienia dotyczy innych danych.")
                return dict(row)
            database.execute("INSERT INTO repair_requests VALUES (?, ?, ?, ?, 'queued', ?, NULL)",
                             (request_id, notification_id, revision, job_id, datetime.now(timezone.utc).isoformat()))
            return None

    def finish_repair(self, request_id, state):
        with self.lock, self.connect() as database:
            database.execute("UPDATE repair_requests SET state = ?, finished = ? WHERE request_id = ?",
                             (state, datetime.now(timezone.utc).isoformat(), request_id))

    def release_repair(self, request_id):
        with self.lock, self.connect() as database:
            database.execute("DELETE FROM repair_requests WHERE request_id = ? AND state = 'queued'", (request_id,))

    def detail(self, notification_id):
        self.validate_id(notification_id)
        with self.lock, self.connect() as database:
            return self.find(database, notification_id)

    def change(self, operation, notification_id=None, confirmed=False):
        if operation not in ("archive", "delete", "restore", "delete-active"):
            raise ValueError("Nieznana operacja powiadomień.")
        if type(confirmed) is not bool:
            raise ValueError("Nieprawidłowe potwierdzenie.")
        if operation in ("delete", "delete-active") and confirmed is not True:
            raise ValueError("Usunięcie wymaga potwierdzenia; powiadomienia trafią do kosza.")
        if operation == "delete-active":
            if notification_id is not None:
                raise ValueError("Operacja zbiorcza nie przyjmuje identyfikatora.")
        else:
            self.validate_id(notification_id)
        timestamp = datetime.now(timezone.utc).isoformat(timespec="microseconds")
        with self.lock, self.connect() as database:
            # Serialize read + update even if a second local server/process uses this SQLite file.
            database.execute("BEGIN IMMEDIATE")
            if operation == "delete-active":
                changed = database.execute("UPDATE notifications SET previous_state = state, state = 'deleted', state_changed_at = ? "
                                           "WHERE state = 'active'", (timestamp,)).rowcount
                item = None
            else:
                current = self.find(database, notification_id)
                target = {"archive": "archived", "delete": "deleted", "restore": "active"}[operation]
                if operation == "archive" and current["state"] == "deleted":
                    raise ValueError("Najpierw przywróć powiadomienie z kosza.")
                if operation == "restore" and current["state"] == "deleted":
                    target = current["previous_state"] or "active"
                changed = 0
                if current["state"] != target:
                    previous = current["state"] if target == "deleted" else None
                    changed = database.execute("UPDATE notifications SET state = ?, previous_state = ?, state_changed_at = ? WHERE id = ?",
                                               (target, previous, timestamp, notification_id)).rowcount
                item = self.find(database, notification_id)
            if changed:
                database.execute("UPDATE notification_revision SET revision = revision + 1 WHERE id = 1")
            return {"changed": changed, "notification": item, "counts": self.counts(database)}

    def page(self, before=None, view="active"):
        if view not in ("active", "archived", "deleted"):
            raise ValueError("Nieznany widok powiadomień.")
        if before is not None and (type(before) is not int or not 1 <= before <= 9223372036854775807):
            raise ValueError("Nieprawidłowy kursor powiadomień.")
        self.scan()
        with self.lock, self.connect() as database:
            database.execute("BEGIN")
            counts = self.counts(database)
            revision = database.execute("SELECT revision FROM notification_revision WHERE id = 1").fetchone()[0]
            rows = database.execute("SELECT id, source, level, message, created, state, previous_state, state_changed_at, issue_code, profile, job_id, outcome, outcome_at, outcome_note FROM notifications "
                                    "WHERE state = ? AND id < ? ORDER BY id DESC LIMIT 51", (view, before or 9223372036854775807)).fetchall()
        return {"total": counts[view], "counts": counts, "view": view, "revision": revision, "items": [dict(row) for row in rows[:50]],
                "next": rows[49]["id"] if len(rows) > 50 else None,
                "sourcesAvailable": any(path.is_file() for _, path in self.sources()),
                "note": "Historia WARN/ERROR banku, sklepu i zadań panelu, również ze znanych archiwów logów DEV. "
                        "Logi z IDE wymagają restartu w DEV z zapisem do .novabank/logs. "
                        "Nie odczytujemy konsoli IDE, logów PROD ani dowolnych katalogów."}
