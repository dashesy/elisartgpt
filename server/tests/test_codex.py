from pathlib import Path

from elisart.codex import parse_events

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
