"""Bounded, redacted import of CLI outcomes. Entries are never executable jobs."""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import threading

from operator_settings import regular, read_file, atomic_json


class LegacyHistory:
    def __init__(self, root, redact, lock=None):
        self.root = Path(root).resolve()
        self.source = self.root / ".novabank/control-center/activity.log"
        self.path = self.root / ".novabank/control-center/web/cli-history.json"
        self.redact = redact
        self.lock = lock or threading.RLock()

    def imported(self):
        raw = read_file(self.path, 1048576)
        if raw is None:
            return []
        try:
            items = json.loads(raw)
            if not isinstance(items, list) or len(items) > 500 or any(not isinstance(x, dict) or set(x) != {"id", "created", "result", "title"}
                    or not re.fullmatch(r"[a-f0-9]{64}", x.get("id", "")) or x.get("result") not in ("OK", "BLAD")
                    or not isinstance(x.get("created"), str) or len(x["created"]) > 30
                    or not isinstance(x.get("title"), str) or len(x["title"]) > 240 for x in items):
                raise ValueError()
            for item in items:
                datetime.fromisoformat(item["created"].replace("Z", "+00:00"))
            return items
        except (ValueError, TypeError, KeyError):
            raise ValueError("Nieprawidłowe archiwum historii CLI. Oryginalny dziennik pozostaje nietknięty.") from None

    def state(self):
        with self.lock:
            items = self.imported()
            # Redact again: newly registered secrets must not leak from an older import.
            safe = [{**x, "title": self.redact(x["title"])} for x in reversed(items)]
            return {"items": safe, "sourceExists": self.source.is_file(), "limit": 500,
                    "note": "Osobny dziennik CLI, profil nieznany. Wpisy nie uruchamiają operacji. Do 500 wpisów, bez modyfikacji activity.log."}

    def import_now(self):
        with self.lock:
            regular(self.source, limit=16 * 1024 * 1024)
            if not self.source.is_file():
                raise ValueError("Brak activity.log terminalowej Centrali.")
            with self.source.open("rb") as stream:
                size = self.source.stat().st_size
                start = max(0, size - 262144)
                stream.seek(start)
                raw = stream.read(262144)
            lines = raw.splitlines()
            if start:
                lines = lines[1:]  # Drop a possibly partial first record.
            items = {x["id"]: x for x in self.imported()}
            added = skipped = 0
            for line in lines:
                parts = line.decode("utf-8", errors="replace").split(" | ", 2)
                if len(parts) != 3 or not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", parts[0]) or not re.fullmatch(r"OK|BLAD:[0-9]{1,3}", parts[1]):
                    skipped += 1
                    continue
                try:
                    datetime.fromisoformat(parts[0].replace("Z", "+00:00"))
                except ValueError:
                    skipped += 1
                    continue
                identifier = hashlib.sha256(line).hexdigest()
                if identifier not in items:
                    title = re.sub(r"[\x00-\x1f\x7f-\x9f]", " ", self.redact(parts[2]))[:240]
                    items[identifier] = {"id": identifier, "created": parts[0], "result": "OK" if parts[1] == "OK" else "BLAD", "title": title}
                    added += 1
            selected = sorted(items.values(), key=lambda x: (x["created"], x["id"]))[-500:]
            atomic_json(self.path, selected, limit=1048576)
            return f"Import CLI: nowych wpisów {added}, pominięto nieprawidłowych {skipped}, zachowano {len(selected)}. Odczytano końcówkę do 256 KB. Oryginału nie zmieniono; wpisy nie są zadaniami do wykonania.\n"
