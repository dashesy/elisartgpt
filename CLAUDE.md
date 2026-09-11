# elisartgpt — Elisa Art

A personal art app: an Android client sends a prompt to a small server on my
VM, the server drives OpenAI Codex CLI (signed in with my ChatGPT account, no
API key), and the generated picture comes back to the phone. One Codex thread
per drawing, so "make it blue" edits the same picture.

## Layout

- `server/` — Python 3.13, FastAPI, `uv`. Spawns `codex exec --json`, parses the
  JSONL events, picks generated PNGs up from `$CODEX_HOME/generated_images/<thread_id>/`.
- `android/` — Kotlin + Jetpack Compose client. Sideloaded APK, no store.
- `deploy/` — VM bootstrap script, Caddyfile, systemd unit.

## The VM

- "The VM" in this repo is whatever `ELISART_VM` in `.env` names (an ssh
  alias), with the checkout at `~/$ELISART_VM_REPO` on it. Nothing
  machine-specific goes in tracked files: no hostnames, IPs, ssh aliases,
  usernames, paths on the VM, cloud resource names or `az` commands with real
  names. All of it lives in `.env` (gitignored), and the Makefile, the deploy
  templates and the Android build read it from there. Docs say
  `$ELISART_PUBLIC_URL`, not the address.
- Caddy terminates HTTPS at `ELISART_PUBLIC_URL` (the public IP spelled under
  sslip.io, so no DNS) and proxies to uvicorn on 127.0.0.1:8787; only 22, 80
  and 443 are open.
- The service is the systemd template `elisart@<user>`. From the laptop:
  `make vm-config` renders the Caddyfile and unit from `.env` and installs
  them; `make deploy` resets the VM checkout to origin/main, syncs and
  restarts; `make vm-status`, `make downloads-on/off`, `make publish`. Invite
  codes are minted on the VM because `data/codes.json` lives there.
- History was rewritten once (`git filter-repo --replace-text`) to scrub such
  details. If one slips in again, scrub it from history the same way rather
  than stacking a "remove" commit on top; `make deploy` resets the VM checkout
  so a force-push is safe.

## Rules

- Secrets never enter the repo. Server config lives in `.env` (see `.env.example`);
  the ChatGPT credential lives only in `~/.codex/auth.json` on the VM.
- The main screen is a chat per drawing: request bubbles (words + photo
  thumbnails) on the right, pictures and the model's line on the left. "Draw
  it!" always sends into the open thread; "+" in the top bar starts a new one
  (like a new chat), so the send button never asks "same or new". The server records `turns` for this;
  the flat `images`/`photos`/`text` fields remain for older app builds.
- Voice: a mic button beside the text box runs Android's `SpeechRecognizer`
  in-app (RECORD_AUDIO, asked once), Persian by default with an English switch
  in Settings; words stream into the box as they are heard. Hints and the
  sample are in spoken Persian; UI labels stay English.
- A request is words plus up to four photos. The app shrinks photos to 1280 px
  JPEG and posts multipart; plain JSON `{"prompt"}` still works for old builds.
  Photos are kept next to the drawing as `in-NNN.jpg`. How the model should
  read them lives in `server/elisart/workspace_agents.md`, not in code.
- Elisa's watercolor (`server/elisart/static/painting.jpg`, face crop in
  `android/.../drawable-nodpi/ic_launcher_painting.png`) is the launcher icon and
  the top of the download page. The empty thread plays back a sample session in
  real bubbles (two photos + Persian sentence -> pink wristband -> "add stars"
  -> stars), with no captions; "Draw it!" is the only send button. Its photos
  ship in `res/raw`.
- People authenticate with an invite code (`make code NAME=elisa`), sent as a
  bearer token and stored hashed in `data/codes.json`. Each code owns its own
  gallery and hourly quota. The app ships with the server URL and asks only
  for the code.
- Tools are pinned in `mise.toml`. Python is always `uv run`, never bare `python`.
- `make help` lists every task. Tests and lint run through the Makefile
  (`make test`, `make lint`; CI runs the same two via `mise-action`).
- `make apk` needs the Android SDK at `~/Library/Android/sdk`; the JDK comes
  from mise. The APK should be ~12 MB; 45 MB means the dex went in uncompressed
  (see `packaging` in `android/app/build.gradle.kts`). `make smoke` draws for
  real and spends the ChatGPT plan; it takes a prompt and photo paths too.
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
- Reference photos go in with `-i <file>` (repeatable, works on `resume` too);
  the prompt follows `--` because `-i` is variadic. `image_gen` uses them as
  input, so "put this wristband on my hand" with two photos really composites.
- Login on a headless box: `codex login --device-auth` (enable device code login
  in ChatGPT security settings first).
