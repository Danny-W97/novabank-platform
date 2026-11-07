"""One owner for each local DEV setting; values never appear in errors."""
import re

PROJECT_KEYS = frozenset({
    "POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER",
    "SHOP_POSTGRES_HOST", "SHOP_POSTGRES_PORT", "SHOP_POSTGRES_DB", "SHOP_POSTGRES_USER",
    "REDIS_HOST", "REDIS_PORT",
    "MAILPIT_SMTP_PORT", "MAILPIT_HTTP_PORT",
})
DEV_PREFIX = re.compile(r"^(?:POSTGRES_|SHOP_|BANK_|REDIS_|MAIL_|EMAIL_|MAILPIT_|METRICS_|TOTP_|GRAFANA_|MANAGEMENT_|ADMIN_|RETENTION_|TRANSFER_|SESSION_|INITIAL_|TERMS_)")
BLOCKED = re.compile(r"(?:^|_)(?:PROD|VAULT|SSL|TLS|KEYSTORE|TRUSTSTORE)(?:_|$)")
APPROVED_DEV_CONFLICTS = frozenset({"BANK_API_KEY", "BANK_API_RATE_LIMIT_PER_MINUTE"})


class OwnershipConflict(ValueError):
    pass


def owner(key):
    if BLOCKED.search(key):
        return None
    if key in PROJECT_KEYS:
        return "project"
    if key == "SPRING_PROFILES_ACTIVE" or DEV_PREFIX.match(key):
        return "dev"
    return None  # Unknown extensions remain untouched, not guessed.


def records(raw):
    result = {}
    for number, line in enumerate((raw or b"").decode("utf-8-sig").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.fullmatch(r"\s*([A-Z][A-Z0-9_]*)\s*=(.*)", line)
        if not match:
            raise OwnershipConflict(f"Nieobsługiwana składnia konfiguracji w linii {number}; nic nie zapisano.")
        key, value = match.groups()
        if key in result:
            raise OwnershipConflict(f"Wielokrotne przypisanie {key}; nic nie zapisano.")
        result[key] = (value.strip().strip("\"'"), line)
    return result


def without(raw, keys):
    """Remove assignments only, retaining comments, BOM and other bytes."""
    if raw is None:
        return None
    bom = b"\xef\xbb\xbf" if raw.startswith(b"\xef\xbb\xbf") else b""
    text = raw.decode("utf-8-sig")
    lines = []
    for line in text.splitlines(keepends=True):
        match = re.match(r"\s*([A-Z][A-Z0-9_]*)\s*=", line)
        if not match or match.group(1) not in keys:
            lines.append(line)
    return bom + "".join(lines).encode("utf-8")


def normalize_pair(snapshot, prefer_dev=()):
    """Route unchanged assignment lines. Conflicts fail closed unless explicitly approved."""
    approved = frozenset(prefer_dev)
    if approved - APPROVED_DEV_CONFLICTS:
        raise OwnershipConflict("Zgoda na konflikt dotyczy tylko klucza API i limitu API DEV.")
    parsed = {name: records(snapshot.get(name)) for name in ("project", "dev")}
    conflicts = sorted(key for key in parsed["project"].keys() & parsed["dev"].keys()
                       if parsed["project"][key][0] != parsed["dev"][key][0])
    if set(conflicts) - approved:
        raise OwnershipConflict("Konflikt konfiguracji: " + ", ".join(key for key in conflicts if key not in approved) + ". Nic nie zapisano.")
    overlap = parsed["project"].keys() & parsed["dev"].keys()
    unknown_duplicates = sorted(key for key in overlap if owner(key) is None)
    if unknown_duplicates:
        raise OwnershipConflict("Brak jednoznacznego właściciela: " + ", ".join(unknown_duplicates) + ". Nic nie zapisano.")
    new = {name: snapshot.get(name) for name in ("project", "dev")}
    additions = {"project": [], "dev": []}
    removals = {"project": set(), "dev": set()}
    moves = []
    for key in sorted(parsed["project"].keys() | parsed["dev"].keys()):
        target = owner(key)
        if target is None:
            continue
        source = "dev" if key in parsed["dev"] else "project"
        if key not in parsed[target]:
            # EnvFile, Java properties and Compose do not share quoting/escape rules.
            # Do not move ambiguous literals merely because our parser compares equal.
            literal = parsed[source][key][1].partition("=")[2]
            if re.search(r"[\s\\$`\"'#]|[^\x21-\x7e]", literal):
                raise OwnershipConflict(f"Format {key} wymaga kontroli różnych loaderów; nic nie zapisano.")
            additions[target].append(parsed[source][key][1])
        elif key in conflicts and target != source:
            raise OwnershipConflict(f"Konflikt {key} wymaga innej decyzji właścicielskiej; nic nie zapisano.")
        other = "dev" if target == "project" else "project"
        if key in parsed[other]:
            removals[other].add(key)
            moves.append({"key": key, "from": other, "to": target, "duplicate": key in parsed[target]})
    for name in new:
        new[name] = without(new[name], removals[name])
        if additions[name]:
            raw = new[name] if new[name] is not None else b"# Lokalna konfiguracja DEV. Nie commituj.\n"
            newline = b"\r\n" if b"\r\n" in raw else b"\n"
            if raw and not raw.endswith((b"\n", b"\r")):
                raw += newline
            new[name] = raw + newline.join(line.encode("utf-8") for line in additions[name]) + newline
    before = {key: entry[0] for key, entry in parsed["project"].items()} | {key: entry[0] for key, entry in parsed["dev"].items()}
    after = {key: entry[0] for key, entry in records(new["project"]).items()} | {key: entry[0] for key, entry in records(new["dev"]).items()}
    if before != after:
        raise OwnershipConflict("Zmieniłaby się efektywna konfiguracja DEV; nic nie zapisano.")
    return new, {"moves": moves, "resolvedUsingDev": conflicts}
