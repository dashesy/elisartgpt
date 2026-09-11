# elisartgpt — Elisa Art

Type what you want to see, get a picture. Add a photo or two and say what to
do with them ("put this wristband on my hand, and make it pink"). An Android app talks to a small
server on my VM; the server drives [Codex CLI](https://github.com/openai/codex)
signed in with my ChatGPT account, and Codex's built-in image generation makes
the picture. No API key, no cloud service of ours in the middle.

```
phone ──HTTPS (Caddy, sslip.io)──▶ server (FastAPI) ──codex exec──▶ ChatGPT plan
                                      ▲                 │
                                      └── copies PNG ◀──┘  ~/.codex/generated_images/<thread>/
```

## Give someone the app

1. `make code NAME=<person>` on the VM and send them the code.
2. Send them the download link (the server's `/` page). They install the APK,
   open it, type the code, and draw.

## Run locally

```
make setup          # mise install, uv sync, hooks, .env
codex login         # once; the server reuses ~/.codex/auth.json
make smoke          # draws one red circle end to end
make dev            # http://127.0.0.1:8787/docs
```

## Deploy

See `deploy/README.md`. Agent and contributor notes: `CLAUDE.md`.
