"""End-to-end check against the real codex login: draws one picture.

Run with `make smoke`. Costs a little of the ChatGPT plan's image quota.
"""

import asyncio
import sys

from elisart import codex
from elisart.settings import Settings


async def main() -> int:
    s = Settings()
    s.prepare_workspace()
    result = await codex.run_turn(
        "Draw a small red circle on a white background.",
        workspace=s.workspace_dir,
        codex_home=s.codex_home,
        codex_bin=s.codex_bin,
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
