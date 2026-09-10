# Deploying to the VM

Target: a small Ubuntu VM (Azure today). Nothing is exposed to the public
internet; the phone reaches the server over Tailscale.

1. `ssh` in and run `deploy/vm-setup.sh`. It installs Tailscale, Node + Codex,
   mise, and clones this repo to `~/elisartgpt`.
2. `sudo tailscale up` and approve the machine in the admin console.
3. `codex login --device-auth` — first enable "device code login" under
   ChatGPT → Settings → Security. Verify with `codex login status`.
4. `cp .env.example .env`, set `ELISART_TOKEN` (`openssl rand -hex 32`) and
   `ELISART_HOST` to the VM's Tailscale IP (`tailscale ip -4`).
5. `sudo cp deploy/elisart.service /etc/systemd/system/ && sudo systemctl enable --now elisart`.

Check: `curl http://<tailscale-ip>:8787/health` from a device on the tailnet.
