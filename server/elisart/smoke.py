"""End-to-end check against the real codex login: draws one picture.

Run with `make smoke`. Costs a little of the ChatGPT plan's image quota.
Extra arguments are a prompt and photos to attach:
`uv run python -m elisart.smoke "put this on my hand" band.jpg hand.jpg`.
"""

import asyncio
import sys
from pathlib import Path

from elisart import codex
from elisart.settings import Settings


async def main() -> int:
    s = Settings()
    s.prepare_workspace()
    prompt = sys.argv[1] if len(sys.argv) > 1 else "Draw a small red circle on a white background."
    result = await codex.run_turn(
        prompt,
        workspace=s.workspace_dir,
        codex_home=s.codex_home,
        codex_bin=s.codex_bin,
        images=[Path(p) for p in sys.argv[2:]],
    )
    print(f"thread:  {result.thread_id}")
    print(f"reply:   {result.text}")
    print(f"images:  {[str(p) for p in result.images]}")
    if result.error:
        print(f"error:   {result.error}", file=sys.stderr)
        return 1
    return 0 if result.images else 2


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
