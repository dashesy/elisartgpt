from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from elisart import codex, main
from elisart.codes import Codes
from elisart.codex import TurnResult


@pytest.fixture
def client(tmp_path: Path, monkeypatch):
    monkeypatch.setattr(main.settings, "data_dir", tmp_path)
    monkeypatch.setattr(main.settings, "rate_limit_per_hour", 2)
    with TestClient(main.app) as c:
        yield c


@pytest.fixture
def code(tmp_path: Path) -> str:
    return Codes(tmp_path / "codes.json").add("elisa")


def _fake_turn(png: Path):
    async def fake_run_turn(prompt, **kw):
        return TurnResult(thread_id="thr", text="A red circle!", images=[png])

    return fake_run_turn


def test_rejects_missing_and_unknown_code(client):
    assert client.get("/drawings").status_code == 401
    assert (
        client.get("/drawings", headers={"Authorization": "Bearer ART-NOPE-NOPE"}).status_code
        == 401
    )


def test_whoami_and_quota(client, code, tmp_path, monkeypatch):
    png = tmp_path / "gen.png"
    png.write_bytes(b"\x89PNG fake")
    monkeypatch.setattr(codex, "run_turn", _fake_turn(png))
    h = {"Authorization": f"Bearer {code.lower()}"}  # case-insensitive on purpose

    assert client.get("/whoami", headers=h).json() == {
        "name": "elisa",
        "drawings_left_this_hour": 2,
    }
    first = client.post("/drawings", json={"prompt": "red circle"}, headers=h)
    assert first.status_code == 200, first.text
    d = first.json()
    assert d["thread_id"] == "thr" and d["images"] == ["001.png"]

    img = client.get(f"/drawings/{d['id']}/images/001.png", headers=h)
    assert img.status_code == 200 and img.content == b"\x89PNG fake"

    assert (
        client.post(f"/drawings/{d['id']}/turns", json={"prompt": "bluer"}, headers=h).status_code
        == 200
    )
    assert client.post("/drawings", json={"prompt": "again"}, headers=h).status_code == 429
    assert (tmp_path / "workspace" / "AGENTS.md").exists()


def test_galleries_are_per_person(client, code, tmp_path, monkeypatch):
    png = tmp_path / "gen.png"
    png.write_bytes(b"png")
    monkeypatch.setattr(codex, "run_turn", _fake_turn(png))
    other = Codes(tmp_path / "codes.json").add("friend")
    client.post("/drawings", json={"prompt": "cat"}, headers={"Authorization": f"Bearer {code}"})
    assert client.get("/drawings", headers={"Authorization": f"Bearer {other}"}).json() == []


def test_revoked_code_is_rejected(client, code, tmp_path):
    Codes(tmp_path / "codes.json").revoke("elisa")
    assert client.get("/whoami", headers={"Authorization": f"Bearer {code}"}).status_code == 401


def test_download_page_without_apk(client):
    r = client.get("/")
    assert r.status_code == 200 and "not uploaded yet" in r.text
    assert client.get("/app/elisart.apk").status_code == 404


def test_app_version(client, tmp_path, monkeypatch):
    assert client.get("/app/version").status_code == 404
    (tmp_path / "app").mkdir()
    (tmp_path / "app" / "version.json").write_text(
        '{"versionCode": 7, "versionName": "0.7-abc", "url": ""}'
    )
    monkeypatch.setattr(main.settings, "public_url", "https://example.test")
    assert client.get("/app/version").json() == {
        "versionCode": 7,
        "versionName": "0.7-abc",
        "url": "https://example.test/app/elisart.apk",
    }


def test_downloads_off_hides_public_surface_only(client, code, monkeypatch):
    monkeypatch.setattr(main.settings, "downloads", False)
    assert client.get("/").status_code == 404
    assert client.get("/app/elisart.apk").status_code == 404
    assert client.get("/app/version").status_code == 404
    assert client.get("/whoami", headers={"Authorization": f"Bearer {code}"}).status_code == 200
