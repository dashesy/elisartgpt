from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from elisart import codex, main
from elisart.codex import TurnResult


@pytest.fixture
def client(tmp_path: Path, monkeypatch):
    monkeypatch.setattr(main.settings, "data_dir", tmp_path)
    monkeypatch.setattr(main.settings, "token", "t")
    with TestClient(main.app) as c:
        yield c


def test_rejects_bad_token(client):
    assert client.get("/drawings").status_code == 401


def test_new_drawing_copies_image(client, tmp_path: Path, monkeypatch):
    png = tmp_path / "gen.png"
    png.write_bytes(b"\x89PNG fake")

    async def fake_run_turn(prompt, **kw):
        return TurnResult(thread_id="thr", text="A red circle!", images=[png])

    monkeypatch.setattr(codex, "run_turn", fake_run_turn)
    headers = {"Authorization": "Bearer t"}

    r = client.post("/drawings", json={"prompt": "red circle"}, headers=headers)
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["thread_id"] == "thr" and d["images"] == ["001.png"]

    img = client.get(f"/drawings/{d['id']}/images/001.png", headers=headers)
    assert img.status_code == 200 and img.content == b"\x89PNG fake"
    assert (tmp_path / "workspace" / "AGENTS.md").exists()
