# Deploying to the VM

Target: a small Ubuntu 24.04 VM with ports 22, 80 and 443 open. Caddy serves
HTTPS on the public IP's sslip.io name; the API listens only on localhost
behind it. Everything machine-specific (ssh alias, checkout path, public URL)
lives in the laptop's `.env`, see `.env.example`; nothing here names a host.

1. On the VM, run `deploy/vm-setup.sh` (optionally `ELISART_VM_REPO=path`
   first). It installs Caddy, Node + Codex, mise, and clones this repo.
2. `codex login --device-auth` — first enable "device code login" under
   ChatGPT → Settings → Security. Verify with `codex login status`.
3. On the VM: `cp .env.example .env` and set `ELISART_PUBLIC_URL`.
4. On the laptop, with `.env` filled in: `make vm-config` renders the Caddyfile
   and the systemd unit from `.env` and installs them.
5. `make code NAME=<person>` on the VM and hand them the code plus the URL.

Check: `curl $ELISART_PUBLIC_URL/health`.

Day to day, from the laptop: `make deploy` (checkout origin/main, `uv sync`,
restart, wait for `/health`), `make publish` (build + upload the APK),
`make vm-status`, `make downloads-on/off`.
