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

## The VM (Azure)

- **In this repo "the VM" means `elisart`**, the ssh alias for the Azure box
  (public IP x.x.x.x, resource group `RESOURCE_GROUP`, REGION, B2ms, Ubuntu
  24.04). It is not the `my_dev_vm` box from the global notes. The `az` CLI is
  logged in to the personal subscription: `az vm show -g RESOURCE_GROUP -n elisart -d`.
- Caddy terminates HTTPS at `https://EXAMPLE.sslip.io` (the IP spelled
  as a hostname, so no DNS) and proxies to uvicorn on 127.0.0.1:8787. The NSG
  `elisart-nsg` opens 22/80/443 only.
- The repo is checked out at `~/elisartgpt` on the VM; the service is
  `elisart@USER` (systemd template in `deploy/`). `make vm-status`,
  `make deploy` (pull + sync + restart), `make downloads-on/off`, `make publish`
  all work from this repo over ssh. Codes are minted on the VM because
  `data/codes.json` lives there.

## Rules

- Secrets never enter the repo. Server config lives in `.env` (see `.env.example`);
  the ChatGPT credential lives only in `~/.codex/auth.json` on the VM.
- People authenticate with an invite code (`make code NAME=elisa`), sent as a
  bearer token and stored hashed in `data/codes.json`. Each code owns its own
  gallery and hourly quota. The app ships with the server URL and asks only
  for the code.
- Tools are pinned in `mise.toml`. Python is always `uv run`, never bare `python`.
- `make help` lists every task. Tests and lint run through the Makefile
  (`make test`, `make lint`; CI runs the same two via `mise-action`).
- `make apk` needs the Android SDK at `~/Library/Android/sdk`; the JDK comes
  from mise. `make smoke` draws for real and spends the ChatGPT plan.
- Comment the *why*, never the *what*.

## The VM

"The VM" here is the Azure box `elisart` (ssh alias of the same name; resource
group `RESOURCE_GROUP`, REGION, Ubuntu 24.04). It runs the API as `elisart@USER`
behind Caddy at https://EXAMPLE.sslip.io. The repo is
cloned at `~/elisartgpt`; deploy = `git pull` + `sudo systemctl restart
elisart@USER`. `make vm-status` from the laptop shows all four services.
`make publish` uploads a new app build; `make downloads-on/off` toggles the
public download page. Invite codes: `make code NAME=<person>` on the VM.

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
