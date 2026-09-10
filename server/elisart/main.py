import shutil
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import FileResponse, HTMLResponse
from pydantic import BaseModel

from elisart import codex
from elisart.codes import Codes
from elisart.settings import Settings

settings = Settings()


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    settings.prepare_workspace()
    yield


app = FastAPI(title="elisart", lifespan=lifespan)


def current_user(authorization: Annotated[str | None, Header()] = None) -> str:
    """The invite code is the credential; its owner's name scopes everything else."""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing code")
    name = Codes(settings.codes_file).lookup(authorization.removeprefix("Bearer "))
    if name is None:
        raise HTTPException(401, "unknown or revoked code")
    return name


User = Annotated[str, Depends(current_user)]


class Prompt(BaseModel):
    prompt: str


class Drawing(BaseModel):
    id: str
    thread_id: str | None
    text: str
    images: list[str]
    error: str | None = None


class AppVersion(BaseModel):
    versionCode: int
    versionName: str
    url: str


class Whoami(BaseModel):
    name: str
    drawings_left_this_hour: int


def _user_dir(user: str) -> Path:
    return settings.drawings_dir / user


def _meta_path(user: str, drawing_id: str) -> Path:
    return _user_dir(user) / drawing_id / "meta.json"


def _load(user: str, drawing_id: str) -> Drawing:
    path = _meta_path(user, drawing_id)
    if not path.exists():
        raise HTTPException(404, "no such drawing")
    return Drawing.model_validate_json(path.read_text())


def _save(user: str, d: Drawing) -> None:
    path = _meta_path(user, d.id)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(d.model_dump_json(indent=2))


def _usage_file(user: str) -> Path:
    return _user_dir(user) / "usage.log"


def _turns_last_hour(user: str) -> int:
    path = _usage_file(user)
    if not path.exists():
        return 0
    cutoff = time.time() - 3600
    return sum(1 for line in path.read_text().splitlines() if line and float(line) > cutoff)


def _record_turn(user: str) -> None:
    path = _usage_file(user)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a") as fp:
        fp.write(f"{time.time()}\n")


@app.get("/health")
def health() -> dict:
    return {"ok": True}


@app.get("/", response_class=HTMLResponse)
def download_page() -> str:
    """Public landing page: install link plus the two-step instruction."""
    has_apk = settings.apk_path.exists()
    link = (
        '<a class="btn" href="/app/elisart.apk">Download the app</a>'
        if has_apk
        else "<p><em>The app is not uploaded yet.</em></p>"
    )
    return f"""<!doctype html><meta charset="utf-8">
<meta name="viewport" content="width=device-width">
<title>Elisa Art</title>
<style>
body{{font-family:system-ui;max-width:28rem;margin:3rem auto;padding:0 1rem;line-height:1.5}}
.btn{{display:inline-block;background:#e91e63;color:#fff;padding:.8rem 1.4rem;
     border-radius:.6rem;text-decoration:none;font-weight:600}}
ol li{{margin:.4rem 0}}
</style>
<h1>🎨 Elisa Art</h1>
<p>Type what you want to see, get a picture.</p>
{link}
<ol><li>Install the app (Android; allow installs from your browser if asked).</li>
<li>Open it and enter the code you were given.</li><li>Draw!</li></ol>"""


@app.get("/app/elisart.apk")
def apk() -> FileResponse:
    if not settings.apk_path.exists():
        raise HTTPException(404, "app not uploaded yet")
    return FileResponse(
        settings.apk_path,
        media_type="application/vnd.android.package-archive",
        filename="elisart.apk",
    )


@app.get("/app/version")
def app_version() -> AppVersion:
    """What the app compares itself against on launch. Public: it holds no secret."""
    if not settings.apk_version_path.exists():
        raise HTTPException(404, "app not uploaded yet")
    v = AppVersion.model_validate_json(settings.apk_version_path.read_text())
    v.url = f"{settings.public_url}/app/elisart.apk"
    return v


@app.get("/whoami")
def whoami(user: User) -> Whoami:
    left = max(0, settings.rate_limit_per_hour - _turns_last_hour(user))
    return Whoami(name=user, drawings_left_this_hour=left)


@app.get("/drawings")
def list_drawings(user: User) -> list[Drawing]:
    root = _user_dir(user)
    if not root.is_dir():
        return []
    items = [_load(user, p.name) for p in root.iterdir() if (p / "meta.json").exists()]
    return sorted(items, key=lambda d: _meta_path(user, d.id).stat().st_mtime, reverse=True)


async def _turn(user: str, d: Drawing, prompt: str) -> Drawing:
    if _turns_last_hour(user) >= settings.rate_limit_per_hour:
        raise HTTPException(429, "that's enough drawings for this hour, try again later")
    _record_turn(user)
    result = await codex.run_turn(
        prompt,
        workspace=settings.workspace_dir,
        codex_home=settings.codex_home,
        codex_bin=settings.codex_bin,
        thread_id=d.thread_id,
    )
    d.thread_id = result.thread_id
    d.text = result.text
    d.error = result.error
    dest = _user_dir(user) / d.id
    dest.mkdir(parents=True, exist_ok=True)
    for src in result.images:
        name = f"{len(d.images) + 1:03d}.png"
        shutil.copy2(src, dest / name)
        d.images.append(name)
    _save(user, d)
    if d.error and not result.images:
        raise HTTPException(502, d.error)
    return d


@app.post("/drawings")
async def new_drawing(user: User, body: Prompt) -> Drawing:
    d = Drawing(id=uuid.uuid4().hex[:12], thread_id=None, text="", images=[])
    return await _turn(user, d, body.prompt)


@app.post("/drawings/{drawing_id}/turns")
async def continue_drawing(user: User, drawing_id: str, body: Prompt) -> Drawing:
    return await _turn(user, _load(user, drawing_id), body.prompt)


@app.get("/drawings/{drawing_id}/images/{name}")
def image(user: User, drawing_id: str, name: str) -> FileResponse:
    d = _load(user, drawing_id)
    if name not in d.images:
        raise HTTPException(404, "no such image")
    return FileResponse(_user_dir(user) / d.id / name, media_type="image/png")
