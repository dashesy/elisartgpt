.DEFAULT_GOAL := help
.PHONY: help setup dev test lint fmt smoke apk publish code codes revoke vm-check vm-config deploy downloads-on downloads-off vm-status emu emu-gui emu-install emu-stop

# Everything machine-specific (VM alias, paths, public URL) comes from .env.
-include .env

SERVER := server
# Gradle needs a JDK; resolve mise's pin even from a shell without `mise activate`.
export JAVA_HOME ?= $(shell mise where java 2>/dev/null)
export ANDROID_HOME ?= $(HOME)/Library/Android/sdk
ADB := $(ANDROID_HOME)/platform-tools/adb

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

VM := $(ELISART_VM)
VM_REPO := $(ELISART_VM_REPO)
PUBLIC_URL := $(ELISART_PUBLIC_URL)
PUBLIC_HOST := $(patsubst https://%,%,$(patsubst http://%,%,$(PUBLIC_URL)))

vm-check:
	@test -n "$(VM)" -a -n "$(VM_REPO)" -a -n "$(PUBLIC_URL)" || { echo "set ELISART_VM, ELISART_VM_REPO and ELISART_PUBLIC_URL in .env"; exit 1; }

# Restart the API on the VM and wait until it answers again (uvicorn takes a second).
define vm_restart
	ssh $(VM) 'cd $(VM_REPO) && sudo systemctl restart elisart@$$USER && for i in $$(seq 20); do curl -sf 127.0.0.1:8787/health >/dev/null && break; sleep 0.5; done'
endef

vm-config: vm-check ## VM: render Caddyfile + systemd unit from .env and install them
	sed 's#{{PUBLIC_HOST}}#$(PUBLIC_HOST)#' deploy/Caddyfile | ssh $(VM) 'sudo tee /etc/caddy/Caddyfile >/dev/null && sudo systemctl reload caddy'
	sed 's#{{VM_REPO}}#$(VM_REPO)#g' deploy/elisart@.service | ssh $(VM) 'sudo tee /etc/systemd/system/elisart@.service >/dev/null && sudo systemctl daemon-reload && sudo systemctl enable --now elisart@$$USER'

# reset --hard, not pull: history gets rewritten when something machine-specific
# slips in, and the VM checkout must follow. .env and data/ are untracked and survive.
deploy: vm-check ## VM: check out origin/main, sync deps, restart the API
	ssh $(VM) 'cd $(VM_REPO) && git fetch -q origin && git reset -q --hard origin/main && (cd server && ~/.local/bin/mise exec -- uv sync -q)'
	$(vm_restart)
	@ssh $(VM) 'cd $(VM_REPO) && git log --oneline -1'

downloads-on: vm-check ## VM: expose the download page + APK
	ssh $(VM) 'cd $(VM_REPO) && sed -i "s/^ELISART_DOWNLOADS=.*/ELISART_DOWNLOADS=true/" .env'
	$(vm_restart)
	@curl -s -o /dev/null -w "download page: %{http_code} (200 = public)\n" $(PUBLIC_URL)/

downloads-off: vm-check ## VM: hide the download page + APK (API keeps working)
	ssh $(VM) 'cd $(VM_REPO) && sed -i "s/^ELISART_DOWNLOADS=.*/ELISART_DOWNLOADS=false/" .env'
	$(vm_restart)
	@curl -s -o /dev/null -w "download page: %{http_code} (404 = hidden)\n" $(PUBLIC_URL)/

vm-status: vm-check ## VM: service state and recent log lines (+ Azure power state if configured)
	@test -z "$(ELISART_AZ_RG)" || az vm show -g $(ELISART_AZ_RG) -n $(ELISART_AZ_VM) -d --query powerState -o tsv
	ssh $(VM) 'systemctl is-active elisart@$$USER caddy | paste -sd" "; journalctl -u elisart@$$USER -n 5 --no-pager -o cat'

code: ## Mint an invite code: make code NAME=elisa
	cd $(SERVER) && uv run python -m elisart.codes add $(NAME)

codes: ## List invite codes
	cd $(SERVER) && uv run python -m elisart.codes list

revoke: ## Revoke someone's code: make revoke NAME=elisa
	cd $(SERVER) && uv run python -m elisart.codes revoke $(NAME)

apk: ## Build the signed release APK (android/keystore.properties; debug key if absent)
	cd android && ./gradlew -q assembleRelease && ls -la app/build/outputs/apk/release/elisart.apk

publish: vm-check apk ## Build and upload the APK (+ version.json for the in-app update check)
	@out=android/app/build/outputs/apk/release; \
	jq '{versionCode: .elements[0].versionCode, versionName: .elements[0].versionName, url: ""}' $$out/output-metadata.json > $$out/version.json; \
	cat $$out/version.json; \
	ssh $(VM) 'mkdir -p $(VM_REPO)/data/app' && scp $$out/elisart.apk $$out/version.json $(VM):$(VM_REPO)/data/app/

emu: ## Boot the headless Android emulator (AVD "elisart") and wait for Android
	@$(ANDROID_HOME)/emulator/emulator -avd elisart -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect >/tmp/elisart-emulator.log 2>&1 &
	@until [ "$$($(ADB) -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done; echo "emulator up: $$($(ADB) -e shell getprop ro.build.version.release)"

emu-gui: ## Boot the emulator WITH a window, to look at the app yourself
	@$(ANDROID_HOME)/emulator/emulator -avd elisart -no-audio -no-boot-anim -no-snapshot >/tmp/elisart-emulator.log 2>&1 &
	@until [ "$$($(ADB) -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done; echo "emulator up: $$($(ADB) -e shell getprop ro.build.version.release)"

emu-install: apk ## Install the release APK on the emulator and launch it
	$(ADB) -e install -r android/app/build/outputs/apk/release/elisart.apk
	$(ADB) -e shell am start -n art.elisa/.MainActivity

emu-stop: ## Shut the emulator down
	-$(ADB) -e emu kill
