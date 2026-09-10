from importlib import resources
from pathlib import Path

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# The repo root: .env and the default data dir live there, whichever cwd we run from.
ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ELISART_", env_file=ROOT / ".env", extra="ignore")

    token: str = "change-me"
    host: str = "127.0.0.1"
    port: int = 8787
    data_dir: Path = ROOT / "data"
    codex_bin: str = "codex"
    # Codex writes generated pictures under $CODEX_HOME/generated_images/<thread_id>/.
    codex_home: Path = Path.home() / ".codex"

    @field_validator("data_dir", mode="after")
    @classmethod
    def _anchor_to_root(cls, v: Path) -> Path:
        # A relative ELISART_DATA_DIR means "relative to the repo", not to the
        # cwd of whoever launched us (make, systemd, a REPL).
        return v if v.is_absolute() else ROOT / v

    @property
    def drawings_dir(self) -> Path:
        return self.data_dir / "drawings"

    @property
    def workspace_dir(self) -> Path:
        return self.data_dir / "workspace"

    def prepare_workspace(self) -> None:
        """Codex reads AGENTS.md from its working directory; that file is what
        turns a coding agent into an art helper."""
        self.workspace_dir.mkdir(parents=True, exist_ok=True)
        agents = resources.files("elisart").joinpath("workspace_agents.md").read_text()
        (self.workspace_dir / "AGENTS.md").write_text(agents)
