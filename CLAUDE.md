# elisartgpt — Alisa Art

A personal art app: an Android client sends a prompt to a small server on my
VM, the server drives OpenAI Codex CLI (signed in with my ChatGPT account, no
API key), and the generated picture comes back to the phone. One Codex thread
per drawing, so "make it blue" edits the same picture.

## Layout

- `server/` — Python 3.13, FastAPI, `uv`. Spawns `codex exec --json`, parses the
  JSONL events, picks generated PNGs up from `$CODEX_HOME/generated_images/<thread_id>/`.
- `android/` — Kotlin + Jetpack Compose client. Sideloaded APK, no store.
- `deploy/` — VM bootstrap (Tailscale, Codex, systemd unit).

## Rules

- Secrets never enter the repo. Server config lives in `.env` (see `.env.example`);
  the ChatGPT credential lives only in `~/.codex/auth.json` on the VM.
- People authenticate with an invite code (`make code NAME=alisa`), sent as a
  bearer token and stored hashed in `data/codes.json`. Each code owns its own
  gallery and hourly quota. The app ships with the server URL and asks only
  for the code.
- Tools are pinned in `mise.toml`. Python is always `uv run`, never bare `python`.
- `make help` lists every task. Tests and lint run through the Makefile.
- Comment the *why*, never the *what*.

## Codex facts this code relies on

- `codex exec --json` prints one JSON object per line: `thread.started`
  (`thread_id`), `turn.started`, `item.started`/`item.completed` (`item.type` is
  `agent_message`, `command_execution`, `reasoning`, ...), `turn.completed`
  (`usage`), `turn.failed`, `error`.
- Images come from the built-in `image_gen` tool (feature `image_generation`,
  stable). There is no image event; the file appears under
  `$CODEX_HOME/generated_images/<thread_id>/`.
- `codex exec resume <thread_id> "<prompt>"` continues a thread; do not pass
  `--ephemeral` or the thread is not persisted.
- Login on a headless box: `codex login --device-auth` (enable device code login
  in ChatGPT security settings first).
