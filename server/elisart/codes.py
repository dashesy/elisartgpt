"""Invite codes: the only credential the app holds.

A code is minted per person (`python -m elisart.codes add elisa`), shown once,
and stored only as a SHA-256 hash. Each code owns its own gallery and quota, so
revoking one person never disturbs another.
"""

import hashlib
import json
import secrets
import sys
from datetime import UTC, datetime
from pathlib import Path

# No 0/O/1/I: codes get read aloud and typed on a phone.
ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def _hash(code: str) -> str:
    return hashlib.sha256(code.strip().upper().encode()).hexdigest()


def _generate() -> str:
    chunk = lambda: "".join(secrets.choice(ALPHABET) for _ in range(4))  # noqa: E731
    return f"ART-{chunk()}-{chunk()}"


class Codes:
    def __init__(self, path: Path):
        self.path = path

    def _load(self) -> dict[str, dict]:
        return json.loads(self.path.read_text()) if self.path.exists() else {}

    def _save(self, data: dict[str, dict]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(data, indent=2))
        self.path.chmod(0o600)

    def add(self, name: str) -> str:
        data = self._load()
        if any(e["name"] == name and not e["revoked"] for e in data.values()):
            raise ValueError(f"an active code for {name!r} already exists; revoke it first")
        code = _generate()
        data[_hash(code)] = {
            "name": name,
            "created": datetime.now(UTC).isoformat(timespec="seconds"),
            "revoked": False,
        }
        self._save(data)
        return code

    def lookup(self, code: str) -> str | None:
        """Return the owner's name for a live code, else None."""
        entry = self._load().get(_hash(code))
        return entry["name"] if entry and not entry["revoked"] else None

    def revoke(self, name: str) -> int:
        data = self._load()
        hit = 0
        for e in data.values():
            if e["name"] == name and not e["revoked"]:
                e["revoked"] = True
                hit += 1
        self._save(data)
        return hit

    def entries(self) -> list[dict]:
        return sorted(self._load().values(), key=lambda e: e["created"])


def main(argv: list[str]) -> int:
    from elisart.settings import Settings

    codes = Codes(Settings().codes_file)
    match argv:
        case ["add", name]:
            print(codes.add(name))
        case ["revoke", name]:
            print(f"revoked {codes.revoke(name)} code(s) for {name}")
        case ["list"]:
            for e in codes.entries():
                state = "revoked" if e["revoked"] else "active"
                print(f"{e['name']:<20} {state:<8} {e['created']}")
        case _:
            print(
                "usage: python -m elisart.codes add <name> | revoke <name> | list", file=sys.stderr
            )
            return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
