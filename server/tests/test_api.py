import asyncio
import time
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
    assert d["turns"][0]["images"] == ["001.png"] and d["turns"][0]["prompt"] == "red circle"

    img = client.get(f"/drawings/{d['id']}/images/001.png", headers=h)
    assert img.status_code == 200 and img.content == b"\x89PNG fake"

    assert (
        client.post(f"/drawings/{d['id']}/turns", json={"prompt": "bluer"}, headers=h).status_code
        == 200
    )
    assert client.post("/drawings", json={"prompt": "again"}, headers=h).status_code == 429
    assert (tmp_path / "workspace" / "AGENTS.md").exists()


def test_photos_are_saved_and_handed_to_codex(client, code, tmp_path, monkeypatch):
    seen = {}

    async def fake_run_turn(prompt, **kw):
        seen.update(prompt=prompt, images=kw["images"], thread_id=kw["thread_id"])
        return TurnResult(thread_id="thr", text="Pink wristband!", images=[])

    monkeypatch.setattr(codex, "run_turn", fake_run_turn)
    h = {"Authorization": f"Bearer {code}"}
    files = [
        ("photos", ("band.jpg", b"\xff\xd8 band", "image/jpeg")),
        ("photos", ("hand.png", b"\x89PNG hand", "image/png")),
    ]
    r = client.post("/drawings", data={"prompt": "put this on my hand"}, files=files, headers=h)
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["photos"] == ["in-001.jpg", "in-002.png"]
    assert seen["prompt"] == "put this on my hand"
    assert [p.name for p in seen["images"]] == ["in-001.jpg", "in-002.png"]
    assert seen["images"][1].read_bytes() == b"\x89PNG hand"

    # A follow-up with one more photo continues the numbering and the thread.
    r = client.post(f"/drawings/{d['id']}/turns", data={"prompt": ""}, files=files[:1], headers=h)
    assert r.status_code == 200, r.text
    assert r.json()["photos"] == ["in-001.jpg", "in-002.png", "in-003.jpg"]
    assert seen["thread_id"] == "thr" and seen["prompt"].startswith("Draw a picture")
    # The conversation keeps each exchange apart; the empty prompt stays empty for display.
    turns = r.json()["turns"]
    assert [t["prompt"] for t in turns] == ["put this on my hand", ""]
    assert turns[0]["photos"] == ["in-001.jpg", "in-002.png"] and turns[1]["photos"] == [
        "in-003.jpg"
    ]
    assert turns[0]["text"] == "Pink wristband!"
    p = client.get(f"/drawings/{d['id']}/photos/in-002.png", headers=h)
    assert p.status_code == 200 and p.content == b"\x89PNG hand"


def test_photo_limits(client, code, tmp_path, monkeypatch):
    (tmp_path / "gen.png").write_bytes(b"png")
    monkeypatch.setattr(codex, "run_turn", _fake_turn(tmp_path / "gen.png"))
    h = {"Authorization": f"Bearer {code}"}
    jpg = ("photos", ("a.jpg", b"x", "image/jpeg"))
    assert (
        client.post("/drawings", data={"prompt": "x"}, files=[jpg] * 5, headers=h).status_code
        == 413
    )
    gif = ("photos", ("a.gif", b"x", "image/gif"))
    assert client.post("/drawings", data={"prompt": "x"}, files=[gif], headers=h).status_code == 415
    assert client.post("/drawings", data={"prompt": "  "}, headers=h).status_code == 422
    assert client.post("/drawings", json={"prompt": " "}, headers=h).status_code == 422
    assert (
        client.post("/drawings", json={"prompt": "no photos, old app"}, headers=h).status_code
        == 200
    )


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
    assert '<img class="painting" src="/painting.jpg"' in r.text
    assert client.get("/app/elisart.apk").status_code == 404
    p = client.get("/painting.jpg")
    assert p.status_code == 200 and p.content[:2] == b"\xff\xd8"


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
    assert client.get("/painting.jpg").status_code == 404
    assert client.get("/whoami", headers={"Authorization": f"Bearer {code}"}).status_code == 200


def test_old_drawing_without_turns_is_shown_as_one(client, code, tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "codex_home", tmp_path / "codex")
    meta = tmp_path / "drawings" / "elisa" / "abc" / "meta.json"
    meta.parent.mkdir(parents=True)
    meta.write_text('{"id":"abc","thread_id":"t","text":"A cat!","images":["001.png"]}')
    d = client.get("/drawings", headers={"Authorization": f"Bearer {code}"}).json()[0]
    assert len(d["turns"]) == 1 and d["turns"][0]["images"] == ["001.png"]
    assert d["turns"][0]["text"] == "A cat!"


def test_old_drawing_is_backfilled_from_codex_log(client, code, tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "codex_home", tmp_path / "codex")
    thread = "01a08e7e-3fbf-70b0-bb05-372758da728c"
    day = tmp_path / "codex" / "sessions" / "2026" / "09" / "11"
    day.mkdir(parents=True)
    (day / f"rollout-x-{thread}.jsonl").write_bytes(
        (Path(__file__).with_name("rollout_fixture.jsonl")).read_bytes()
    )
    meta = tmp_path / "drawings" / "elisa" / "abc" / "meta.json"
    meta.parent.mkdir(parents=True)
    # As saved by the interim server: a wordless placeholder turn plus one real turn.
    meta.write_text(
        f'{{"id":"abc","thread_id":"{thread}","text":"stars","images":["001.png","002.png"],'
        '"photos":["in-001.jpg","in-002.jpg"],"turns":[{"prompt":"","images":["001.png"],"at":1},'
        '{"prompt":"now put a tiny yellow star on each bead","images":["002.png"],'
        '"text":"stars","at":2}]}'
    )
    h = {"Authorization": f"Bearer {code}"}
    turns = client.get("/drawings", headers=h).json()[0]["turns"]
    assert [t["images"] for t in turns] == [["001.png"], ["002.png"]]
    assert turns[0]["photos"] == ["in-001.jpg", "in-002.jpg"] and turns[0]["prompt"].startswith(
        "این"
    )
    # Backfilled once, then stored: the meta file now carries the turns.
    assert '"turns"' in meta.read_text() and "star" in meta.read_text()


def test_delete_drawing(client, code, tmp_path, monkeypatch):
    (tmp_path / "gen.png").write_bytes(b"png")
    monkeypatch.setattr(codex, "run_turn", _fake_turn(tmp_path / "gen.png"))
    h = {"Authorization": f"Bearer {code}"}
    d = client.post("/drawings", json={"prompt": "cat"}, headers=h).json()
    other = Codes(tmp_path / "codes.json").add("friend")
    # Someone else's code cannot delete it.
    assert (
        client.delete(
            f"/drawings/{d['id']}", headers={"Authorization": f"Bearer {other}"}
        ).status_code
        == 404
    )
    assert client.delete(f"/drawings/{d['id']}", headers=h).status_code == 204
    assert client.get("/drawings", headers=h).json() == []
    assert not (tmp_path / "drawings" / "elisa" / d["id"]).exists()
    assert client.delete(f"/drawings/{d['id']}", headers=h).status_code == 404


def test_ask_is_answered_in_words(client, code, tmp_path, monkeypatch):
    seen = {}

    async def fake_run_turn(prompt, **kw):
        seen["prompt"] = prompt
        return TurnResult(thread_id="thr", text="Cats have whiskers to feel their way!", images=[])

    monkeypatch.setattr(codex, "run_turn", fake_run_turn)
    h = {"Authorization": f"Bearer {code}"}
    r = client.post(
        "/drawings", json={"prompt": "why do cats have whiskers?", "mode": "ask"}, headers=h
    )
    assert r.status_code == 200, r.text
    t = r.json()["turns"][0]
    assert t["kind"] == "ask" and t["prompt"] == "why do cats have whiskers?" and t["images"] == []
    assert "whiskers" in t["text"]
    assert seen["prompt"].startswith("why do cats") and seen["prompt"].endswith("No picture.]")
    # Old builds send no mode and get a drawing turn with a clean prompt.
    client.post("/drawings", json={"prompt": "a cat"}, headers=h)
    assert seen["prompt"] == "a cat"


def _settle(client, h, drawing_id: str) -> dict:
    """Poll like the phone does until the server is done with the drawing."""
    for _ in range(100):
        d = client.get(f"/drawings/{drawing_id}", headers=h).json()
        if d["pending"] is None:
            return d
        time.sleep(0.02)
    raise AssertionError("turn never finished")


def test_turn_runs_in_the_background_and_is_polled_for(client, code, tmp_path, monkeypatch):
    png = tmp_path / "gen.png"
    png.write_bytes(b"png")
    calls = []

    async def slow(prompt, **kw):
        calls.append(prompt)
        await asyncio.sleep(0.15)
        return TurnResult(thread_id="thr", text="A cat!", images=[png])

    monkeypatch.setattr(codex, "run_turn", slow)
    h = {"Authorization": f"Bearer {code}"}
    body = {"prompt": "a cat", "id": "abcdef012345"}
    r = client.post("/drawings?wait=false", json=body, headers=h)
    assert r.status_code == 202, r.text
    d = r.json()
    assert d["id"] == "abcdef012345" and d["turns"] == [] and d["images"] == []
    assert d["pending"]["prompt"] == "a cat" and d["pending"]["kind"] == "draw"
    # Visible to a poll, and in the list, while it runs.
    assert client.get("/drawings/abcdef012345", headers=h).json()["pending"]["prompt"] == "a cat"
    assert client.get("/drawings", headers=h).json()[0]["pending"]["prompt"] == "a cat"
    # The same request again (the phone lost the answer) is not drawn twice...
    again = client.post("/drawings?wait=false", json=body, headers=h)
    assert again.status_code == 202 and again.json()["pending"]["prompt"] == "a cat"
    # ...and a different one on the same drawing is told to wait, not handed to codex.
    r2 = client.post("/drawings/abcdef012345/turns?wait=false", json={"prompt": "bluer"}, headers=h)
    assert r2.status_code == 409, r2.text

    d = _settle(client, h, "abcdef012345")
    assert d["turns"][0]["images"] == ["001.png"] and d["turns"][0]["text"] == "A cat!"
    assert d["error"] is None and calls == ["a cat"]
    # Charged once. The pending marker is not written to disk.
    assert client.get("/whoami", headers=h).json()["drawings_left_this_hour"] == 1
    assert (
        "pending"
        not in (tmp_path / "drawings" / "elisa" / "abcdef012345" / "meta.json").read_text()
    )
    # Once finished, re-sending the original request just returns the drawing.
    assert client.post("/drawings?wait=false", json=body, headers=h).status_code == 200


def test_a_failed_background_turn_leaves_the_error_on_the_drawing(client, code, monkeypatch):
    async def broken(prompt, **kw):
        await asyncio.sleep(0.05)
        return TurnResult(thread_id="thr", error="codex fell over")

    monkeypatch.setattr(codex, "run_turn", broken)
    h = {"Authorization": f"Bearer {code}"}
    d = client.post("/drawings?wait=false", json={"prompt": "a cat"}, headers=h).json()
    d = _settle(client, h, d["id"])
    assert d["turns"] == [] and d["error"] == "codex fell over"
    # Old builds that wait get the same failure as before.
    assert client.post("/drawings", json={"prompt": "a dog"}, headers=h).status_code == 502


def test_bad_client_ids_are_refused(client, code):
    h = {"Authorization": f"Bearer {code}"}
    assert (
        client.post("/drawings", json={"prompt": "x", "id": "../etc"}, headers=h).status_code == 422
    )
    assert (
        client.post("/drawings", json={"prompt": "x", "id": "ABCDEF012345"}, headers=h).status_code
        == 422
    )
    assert client.get("/drawings/abcdef012345", headers=h).status_code == 404


def test_deleting_a_drawing_stops_its_turn(client, code, monkeypatch):
    stopped = asyncio.Event()

    async def forever(prompt, **kw):
        try:
            await asyncio.sleep(30)
        except asyncio.CancelledError:
            stopped.set()
            raise
        return TurnResult(thread_id="thr")

    monkeypatch.setattr(codex, "run_turn", forever)
    h = {"Authorization": f"Bearer {code}"}
    d = client.post("/drawings?wait=false", json={"prompt": "a cat"}, headers=h).json()
    assert client.delete(f"/drawings/{d['id']}", headers=h).status_code == 204
    for _ in range(100):
        if stopped.is_set():
            break
        time.sleep(0.02)
    assert stopped.is_set() and d["id"] not in main.jobs
    assert client.get(f"/drawings/{d['id']}", headers=h).status_code == 404
