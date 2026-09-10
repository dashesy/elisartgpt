# elisartgpt — Elisa Art

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
- People authenticate with an invite code (`make code NAME=elisa`), sent as a
  bearer token and stored hashed in `data/codes.json`. Each code owns its own
  gallery and hourly quota. The app ships with the server URL and asks only
  for the code.
- Tools are pinned in `mise.toml`. Python is always `uv run`, never bare `python`.
- `make help` lists every task. Tests and lint run through the Makefile.
- Comment the *why*, never the *what*.

## Testing the app without a phone

An Android emulator is set up on the Mac (SDK at `~/Library/Android/sdk`, AVD
`elisart` = Pixel 7, Android 15, arm64). Drive it from the shell:

```
make emu            # boot headless (no window); ~20 s to Android
make emu-gui        # same, with a window, for a human to look at
make emu-install    # build + install the release APK and launch the app
adb -e exec-out screencap -p > shot.png    # see the screen (Read the PNG)
adb -e shell input tap X Y / input text 'a%sb' / input keyevent KEYCODE_BACK
adb -e logcat -d -s AndroidRuntime:E        # crashes
make emu-stop
```

Screen is 1080x2400. `input text` needs `%s` for spaces. A drawing takes
30-90 s: screenshot again after `sleep 60`. Gotchas that cost time once:
`avdmanager` only finds system images when it runs from a *copy* of
`cmdline-tools` under the SDK root (not the Homebrew path, not a symlink);
Gradle/adb tooling needs `JAVA_HOME` (`mise where java`); and a fresh AVD has
`hw.keyboard=no`, so the Mac keyboard does nothing in the window until
`~/.android/avd/elisart.avd/config.ini` says `hw.keyboard = yes`.

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
