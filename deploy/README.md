# Deploying to the VM

Target: the Azure VM `elisart` (Ubuntu 24.04, ssh alias of the same name).
Caddy serves HTTPS on the public IP's sslip.io name; the API listens only on
localhost behind it. Ports 22, 80 and 443 are open in the VM's NSG.

1. `ssh elisart` and run `deploy/vm-setup.sh`. It installs Caddy, Node + Codex,
   mise, and clones this repo to `~/elisartgpt`.
2. `codex login --device-auth` — first enable "device code login" under
   ChatGPT → Settings → Security. Verify with `codex login status`.
3. `cp .env.example .env`; set `ELISART_PUBLIC_URL=https://EXAMPLE.sslip.io`.
4. `sudo cp deploy/Caddyfile /etc/caddy/Caddyfile && sudo systemctl reload caddy`.
5. `sudo cp deploy/elisart@.service /etc/systemd/system/ && sudo systemctl enable --now elisart@$USER`.
6. `make code NAME=<person>` and hand them the code plus the public URL.

Check: `curl https://EXAMPLE.sslip.io/health`.

Ship server changes: `make deploy` (pull, `uv sync`, restart, wait for
`/health`). Publish a new app build: `make publish`.
