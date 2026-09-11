#!/usr/bin/env bash
# One-shot bootstrap for a fresh Ubuntu VM. Idempotent; safe to rerun.
set -euo pipefail

sudo apt-get update -y
sudo apt-get install -y curl git build-essential

# Caddy terminates HTTPS in front of the server (official repo; Ubuntu's is old).
if ! command -v caddy >/dev/null; then
  sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | sudo gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt | sudo tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
  sudo apt-get update -y && sudo apt-get install -y caddy
fi

# Codex CLI ships on npm. Node 22 via NodeSource keeps it independent of mise.
if ! command -v node >/dev/null; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
  sudo apt-get install -y nodejs
fi
sudo npm install -g @openai/codex

# mise gives the repo its pinned Python/uv.
command -v mise >/dev/null || { curl https://mise.run | sh; echo 'eval "$($HOME/.local/bin/mise activate bash)"' >> ~/.bashrc; }
export PATH="$HOME/.local/bin:$PATH"

# Checkout path relative to $HOME; keep it in sync with ELISART_VM_REPO in the laptop's .env.
REPO_DIR="$HOME/${ELISART_VM_REPO:-elisartgpt}"
[ -d "$REPO_DIR" ] || git clone https://github.com/dashesy/elisartgpt.git "$REPO_DIR"
# Only the server toolchain: the JDK/ktlint pins are for building the Android app.
cd "$REPO_DIR" && mise trust && mise install python uv && (cd server && mise exec -- uv sync)

echo "next: codex login --device-auth; then follow deploy/README.md"
