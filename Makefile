.DEFAULT_GOAL := help
.PHONY: help setup dev test lint fmt smoke apk

SERVER := server

help: ## List tasks
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

setup: ## One-time: tools, deps, hooks, .env
	mise install
	cd $(SERVER) && uv sync
	mise exec -- lefthook install
	@test -f .env || { cp .env.example .env; echo "created .env — set ELISART_TOKEN"; }

dev: ## Run the server locally with reload
	cd $(SERVER) && uv run uvicorn elisart.main:app --reload --host $${ELISART_HOST:-127.0.0.1} --port $${ELISART_PORT:-8787}

test: ## Unit tests (no codex needed)
	cd $(SERVER) && uv run pytest -q

lint: ## Lint + format check
	cd $(SERVER) && uv run ruff check . && uv run ruff format --check .

fmt: ## Format
	cd $(SERVER) && uv run ruff format . && uv run ruff check --fix .

smoke: ## Generate one picture through codex end to end (uses your plan)
	cd $(SERVER) && uv run python -m elisart.smoke

apk: ## Build the debug APK
	cd android && ./gradlew assembleDebug
