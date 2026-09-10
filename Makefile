.DEFAULT_GOAL := help
.PHONY: help setup dev test lint fmt smoke apk publish code codes revoke

SERVER := server
# Gradle needs a JDK; resolve mise's pin even from a shell without `mise activate`.
export JAVA_HOME ?= $(shell mise where java 2>/dev/null)

help: ## List tasks
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

setup: ## One-time: tools, deps, hooks, .env
	mise install
	cd $(SERVER) && uv sync
	mise exec -- lefthook install
	@test -f .env || { cp .env.example .env; echo "created .env"; }

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

code: ## Mint an invite code: make code NAME=elisa
	cd $(SERVER) && uv run python -m elisart.codes add $(NAME)

codes: ## List invite codes
	cd $(SERVER) && uv run python -m elisart.codes list

revoke: ## Revoke someone's code: make revoke NAME=elisa
	cd $(SERVER) && uv run python -m elisart.codes revoke $(NAME)

apk: ## Build the signed release APK (android/keystore.properties; debug key if absent)
	cd android && ./gradlew -q assembleRelease && ls -la app/build/outputs/apk/release/elisart.apk

publish: apk ## Build and upload the APK (+ version.json for the in-app update check)
	@out=android/app/build/outputs/apk/release; \
	jq '{versionCode: .elements[0].versionCode, versionName: .elements[0].versionName, url: ""}' $$out/output-metadata.json > $$out/version.json; \
	cat $$out/version.json; \
	ssh elisart 'mkdir -p elisartgpt/data/app' && scp $$out/elisart.apk $$out/version.json elisart:elisartgpt/data/app/
