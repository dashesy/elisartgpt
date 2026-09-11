from pathlib import Path

from elisart.codex import build_argv, parse_events

FIXTURE = Path(__file__).with_name("events_fixture.jsonl")


def test_parse_real_run():
    r = parse_events(FIXTURE.read_text().splitlines())
    assert r.thread_id == "01a08d5f-1cf4-7a10-a5f9-22b2f2758468"
    assert r.text.endswith("circle.png")
    assert r.error is None
    assert r.usage["output_tokens"] == 352


def test_parse_failure_and_garbage():
    lines = [
        "not json",
        '{"type":"thread.started","thread_id":"t1"}',
        '{"type":"turn.failed","error":{"message":"boom"}}',
    ]
    r = parse_events(lines)
    assert r.thread_id == "t1"
    assert r.error == "boom"


def test_relative_data_dir_is_anchored_to_repo(monkeypatch):
    from elisart.settings import ROOT, Settings

    monkeypatch.setenv("ELISART_DATA_DIR", "somewhere")
    assert Settings().data_dir == ROOT / "somewhere"


def test_argv_attaches_images_and_guards_the_prompt(tmp_path):
    imgs = [tmp_path / "a.jpg", tmp_path / "b.png"]
    new = build_argv(
        "-i looks like a flag", workspace=tmp_path, codex_bin="codex", thread_id=None, images=imgs
    )
    assert new[-1] == "-i looks like a flag" and new[-2] == "--"
    assert new[new.index("-C") + 1] == str(tmp_path.resolve())
    assert [new[i + 1] for i, a in enumerate(new) if a == "-i"] == [str(p) for p in imgs]

    cont = build_argv("bluer", workspace=tmp_path, codex_bin="codex", thread_id="t1", images=[])
    assert cont[cont.index("resume") + 1] == "t1" and "-C" not in cont and "-i" not in cont
    assert cont[-2:] == ["--", "bluer"]
