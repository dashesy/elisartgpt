"""Drive `codex exec --json` and collect the picture it generates.

Codex has no image event: the built-in image_gen tool drops PNGs under
$CODEX_HOME/generated_images/<thread_id>/ and only the agent's shell commands
mention the path. So the reliable signal is the directory itself, diffed
before and after the turn.
"""

import asyncio
import json
from dataclasses import dataclass, field
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


def _pngs(directory: Path) -> set[Path]:
    return set(directory.glob("*.png")) if directory.is_dir() else set()


async def run_turn(
    prompt: str,
    *,
    workspace: Path,
    codex_home: Path,
    codex_bin: str = "codex",
    thread_id: str | None = None,
    timeout: float = 300,
) -> TurnResult:
    """Run one turn. Pass thread_id to continue an existing drawing."""
    # Absolute: `-C` is resolved by codex against its own cwd, which is also this dir.
    workspace = workspace.resolve()
    workspace.mkdir(parents=True, exist_ok=True)
    argv = [codex_bin, "exec", "--json", "--skip-git-repo-check", "-s", "workspace-write"]
    if thread_id:
        argv += ["resume", thread_id, prompt]
    else:
        argv += ["-C", str(workspace), prompt]

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
