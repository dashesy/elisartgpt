#!/usr/bin/env bash
# One-shot bootstrap for a fresh Ubuntu VM. Idempotent; safe to rerun.
set -euo pipefail

sudo apt-get update -y
sudo apt-get install -y curl git build-essential

# Tailscale: the only network path to the server.
command -v tailscale >/dev/null || curl -fsSL https://tailscale.com/install.sh | sh

# Codex CLI ships on npm. Node 22 via NodeSource keeps it independent of mise.
if ! command -v node >/dev/null; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
  sudo apt-get install -y nodejs
fi
sudo npm install -g @openai/codex

# mise gives the repo its pinned Python/uv.
command -v mise >/dev/null || { curl https://mise.run | sh; echo 'eval "$($HOME/.local/bin/mise activate bash)"' >> ~/.bashrc; }
export PATH="$HOME/.local/bin:$PATH"

[ -d ~/elisartgpt ] || git clone https://github.com/dashesy/elisartgpt.git ~/elisartgpt
cd ~/elisartgpt && mise trust && mise install && (cd server && mise exec -- uv sync)

echo "next: sudo tailscale up; codex login --device-auth; see deploy/README.md"
