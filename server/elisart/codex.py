"""Drive `codex exec --json` and collect the picture it generates.

Codex has no image event: the built-in image_gen tool drops PNGs under
$CODEX_HOME/generated_images/<thread_id>/ and only the agent's shell commands
mention the path. So the reliable signal is the directory itself, diffed
before and after the turn.
"""

import asyncio
import json
import re
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path


@dataclass
class TurnResult:
    thread_id: str | None = None
    text: str = ""
    images: list[Path] = field(default_factory=list)
    error: str | None = None
    usage: dict | None = None


def parse_events(lines: list[str], result: TurnResult | None = None) -> TurnResult:
    """Fold the JSONL event stream into a TurnResult. Non-JSON lines are skipped."""
    result = result or TurnResult()
    for raw in lines:
        raw = raw.strip()
        if not raw:
            continue
        try:
            ev = json.loads(raw)
        except json.JSONDecodeError:
            continue
        kind = ev.get("type")
        if kind == "thread.started":
            result.thread_id = ev.get("thread_id")
        elif kind == "item.completed" and ev.get("item", {}).get("type") == "agent_message":
            # The last message is the reply shown to the user; earlier ones are narration.
            result.text = ev["item"].get("text", "")
        elif kind == "turn.completed":
            result.usage = ev.get("usage")
        elif kind in ("turn.failed", "error"):
            err = ev.get("error", {}).get("message") if kind == "turn.failed" else ev.get("message")
            result.error = err or kind
    return result


@dataclass
class LoggedTurn:
    prompt: str
    photos: list[str]
    text: str
    at: float


def _rollout(codex_home: Path, thread_id: str) -> Path | None:
    hits = sorted((codex_home / "sessions").glob(f"*/*/*/rollout-*-{thread_id}.jsonl"))
    return hits[-1] if hits else None


def thread_turns(codex_home: Path, thread_id: str) -> list[LoggedTurn]:
    """Recover the conversation from codex's own session log: each real user
    message (not the AGENTS.md / environment blobs codex injects, which start
    with `#` or `<`) opens a turn, `<image path=...>` items name its photos, and
    the following assistant message is its reply. Used for drawings saved
    before turns were recorded."""
    path = _rollout(codex_home, thread_id)
    if path is None:
        return []
    turns: list[LoggedTurn] = []
    for raw in path.read_text(errors="replace").splitlines():
        try:
            ev = json.loads(raw)
        except json.JSONDecodeError:
            continue
        msg = ev.get("payload") or {}
        if ev.get("type") != "response_item" or msg.get("type") != "message":
            continue
        texts = [c.get("text", "") for c in msg.get("content") or [] if isinstance(c, dict)]
        if msg.get("role") == "user":
            photos = [
                Path(m.group(1)).name
                for t in texts
                for m in re.finditer(r'<image [^>]*path="([^"]+)"', t)
            ]
            prompt = next(
                (t for t in texts if t.strip() and not t.lstrip().startswith(("<", "#"))), None
            )
            if prompt is None and not photos:
                continue
            try:
                at = datetime.fromisoformat(
                    ev.get("timestamp", "").replace("Z", "+00:00")
                ).timestamp()
            except ValueError:
                at = 0.0
            turns.append(LoggedTurn(prompt=(prompt or "").strip(), photos=photos, text="", at=at))
        elif msg.get("role") == "assistant" and turns:
            turns[-1].text = "\n".join(t for t in texts if t.strip()).strip()
    return turns


def _pngs(directory: Path) -> set[Path]:
    return set(directory.glob("*.png")) if directory.is_dir() else set()


def build_argv(
    prompt: str,
    *,
    workspace: Path,
    codex_bin: str,
    thread_id: str | None,
    images: list[Path],
) -> list[str]:
    argv = [codex_bin, "exec", "--json", "--skip-git-repo-check", "-s", "workspace-write"]
    # `-C` is resolved by codex against its own cwd, which is also this dir, so absolute.
    argv += ["resume", thread_id] if thread_id else ["-C", str(workspace.resolve())]
    # `-i` is variadic: one flag per file, and `--` so the prompt is never taken for a path.
    for img in images:
        argv += ["-i", str(img.resolve())]
    argv += ["--", prompt]
    return argv


async def run_turn(
    prompt: str,
    *,
    workspace: Path,
    codex_home: Path,
    codex_bin: str = "codex",
    thread_id: str | None = None,
    images: list[Path] | None = None,
    timeout: float = 300,
) -> TurnResult:
    """Run one turn. Pass thread_id to continue an existing drawing; `images` are
    the user's photos, attached to the prompt so the model can draw from them."""
    workspace = workspace.resolve()
    workspace.mkdir(parents=True, exist_ok=True)
    argv = build_argv(
        prompt, workspace=workspace, codex_bin=codex_bin, thread_id=thread_id, images=images or []
    )

    gen_root = codex_home / "generated_images"
    before = _pngs(gen_root / thread_id) if thread_id else set()

    proc = await asyncio.create_subprocess_exec(
        *argv,
        cwd=workspace,
        # Without this codex notices a piped stdin and waits to read it.
        stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout)
    except TimeoutError:
        proc.kill()
        return TurnResult(thread_id=thread_id, error=f"codex timed out after {timeout:.0f}s")

    result = parse_events(stdout.decode(errors="replace").splitlines())
    if result.thread_id is None:
        result.thread_id = thread_id
    if proc.returncode != 0 and not result.error:
        result.error = stderr.decode(errors="replace").strip()[-500:] or f"exit {proc.returncode}"

    if result.thread_id:
        after = _pngs(gen_root / result.thread_id)
        result.images = sorted(after - before, key=lambda p: p.stat().st_mtime)
    return result
