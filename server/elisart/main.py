import shutil
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from importlib import resources
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, Response
from pydantic import BaseModel, ValidationError

# Parsed forms yield Starlette's class, not FastAPI's subclass, so check for this one.
from starlette.datastructures import UploadFile

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


# Photos a person attaches to a request. Phones downscale before uploading, so
# these caps only stop mistakes; the real cost is the model looking at them.
MAX_PHOTOS = 4
MAX_PHOTO_BYTES = 8 * 1024 * 1024
PHOTO_TYPES = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}


class Turn(BaseModel):
    """One exchange: what the person said and attached, what came back."""

    prompt: str
    photos: list[str] = []
    images: list[str] = []
    text: str = ""
    at: float


class Drawing(BaseModel):
    id: str
    thread_id: str | None
    text: str
    images: list[str]
    # Uploaded reference photos, in the order they were attached across turns.
    photos: list[str] = []
    # The conversation, oldest first. `images`/`photos`/`text` above are the
    # flattened view older app builds still read.
    turns: list[Turn] = []
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
    d = Drawing.model_validate_json(path.read_text())
    # A turn with neither words nor photos cannot be created any more; one in the
    # file is a placeholder from before turns were recorded and wants backfilling.
    if d.images and (not d.turns or any(not t.prompt and not t.photos for t in d.turns)):
        d.turns = _backfill_turns(d, path.stat().st_mtime)
        _save(user, d)
    return d


def _backfill_turns(d: Drawing, mtime: float) -> list[Turn]:
    """Drawings saved before turns were recorded: rebuild the exchanges from
    codex's session log, or failing that show the pictures as one exchange."""
    logged = codex.thread_turns(settings.codex_home, d.thread_id) if d.thread_id else []
    if not logged:
        return d.turns or [Turn(prompt="", images=d.images, text=d.text, at=mtime)]
    turns = [
        Turn(
            prompt=t.prompt,
            photos=[p for p in t.photos if p in d.photos],
            text=t.text,
            at=t.at or mtime,
        )
        for t in logged
    ]
    # One picture per turn is the norm; anything left over belongs to the last one.
    for i, name in enumerate(d.images):
        turns[min(i, len(turns) - 1)].images.append(name)
    return turns


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


def _require_downloads() -> None:
    if not settings.downloads:
        raise HTTPException(404, "not found")


@app.get("/painting.jpg")
def painting() -> Response:
    """Elisa's own watercolor, the face of the app and the top of the landing page."""
    _require_downloads()
    data = resources.files("elisart").joinpath("static/painting.jpg").read_bytes()
    return Response(data, media_type="image/jpeg", headers={"Cache-Control": "max-age=86400"})


@app.get("/", response_class=HTMLResponse)
def download_page() -> str:
    """Public landing page: the painting, the install link, the two-step instruction."""
    _require_downloads()
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
body{{font-family:system-ui;max-width:28rem;margin:1.5rem auto 3rem;padding:0 1rem;line-height:1.5}}
.painting{{display:block;width:100%;max-height:70vh;object-fit:cover;object-position:top;
          border-radius:1rem;box-shadow:0 8px 24px rgba(0,0,0,.15)}}
.btn{{display:inline-block;background:#e91e63;color:#fff;padding:.8rem 1.4rem;
     border-radius:.6rem;text-decoration:none;font-weight:600}}
ol li{{margin:.4rem 0}}
</style>
<img class="painting" src="/painting.jpg" alt="Elisa's watercolor of a girl with a sea in her hair">
<h1>🎨 Elisa Art</h1>
<p>Type what you want to see, get a picture.</p>
{link}
<ol><li>Install the app (Android; allow installs from your browser if asked).</li>
<li>Open it and enter the code you were given.</li><li>Draw!</li></ol>"""


@app.get("/app/elisart.apk")
def apk() -> FileResponse:
    _require_downloads()
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
    _require_downloads()
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


async def _read_request(request: Request) -> tuple[str, list[UploadFile]]:
    """A request is JSON (`{"prompt"}`, what older phones send) or a form with a
    `prompt` field and up to MAX_PHOTOS `photos` files."""
    if request.headers.get("content-type", "").startswith("application/json"):
        try:
            prompt = Prompt.model_validate_json(await request.body()).prompt.strip()
        except ValidationError as e:
            raise HTTPException(422, "bad request body") from e
        if not prompt:
            raise HTTPException(422, "say what to draw, or add a photo")
        return prompt, []
    form = await request.form()
    photos = [f for f in form.getlist("photos") if isinstance(f, UploadFile)]
    if len(photos) > MAX_PHOTOS:
        raise HTTPException(413, f"at most {MAX_PHOTOS} photos per request")
    for f in photos:
        if f.content_type not in PHOTO_TYPES:
            raise HTTPException(415, "photos must be JPEG, PNG or WebP")
        if f.size is not None and f.size > MAX_PHOTO_BYTES:
            raise HTTPException(413, "that photo is too big")
    prompt = str(form.get("prompt", "")).strip()
    if not prompt and not photos:
        raise HTTPException(422, "say what to draw, or add a photo")
    return prompt, photos


async def _save_photos(user: str, d: Drawing, photos: list[UploadFile]) -> list[Path]:
    dest = _user_dir(user) / d.id
    dest.mkdir(parents=True, exist_ok=True)
    paths = []
    for f in photos:
        name = f"in-{len(d.photos) + 1:03d}.{PHOTO_TYPES[f.content_type or '']}"
        (dest / name).write_bytes(await f.read())
        d.photos.append(name)
        paths.append(dest / name)
    return paths


async def _turn(user: str, d: Drawing, prompt: str, photos: list[UploadFile]) -> Drawing:
    if _turns_last_hour(user) >= settings.rate_limit_per_hour:
        raise HTTPException(429, "that's enough drawings for this hour, try again later")
    _record_turn(user)
    photo_paths = await _save_photos(user, d, photos)
    turn = Turn(prompt=prompt, photos=[p.name for p in photo_paths], at=time.time())
    result = await codex.run_turn(
        # A photo with no words is a complete request; give the model something to answer.
        prompt or "Draw a picture from these photos.",
        workspace=settings.workspace_dir,
        codex_home=settings.codex_home,
        codex_bin=settings.codex_bin,
        thread_id=d.thread_id,
        images=photo_paths,
    )
    d.thread_id = result.thread_id
    d.text = result.text
    d.error = result.error
    dest = _user_dir(user) / d.id
    for src in result.images:
        name = f"{len(d.images) + 1:03d}.png"
        shutil.copy2(src, dest / name)
        d.images.append(name)
        turn.images.append(name)
    turn.text = result.text
    d.turns.append(turn)
    _save(user, d)
    if d.error and not result.images:
        raise HTTPException(502, d.error)
    return d


@app.post("/drawings")
async def new_drawing(user: User, request: Request) -> Drawing:
    prompt, photos = await _read_request(request)
    d = Drawing(id=uuid.uuid4().hex[:12], thread_id=None, text="", images=[])
    return await _turn(user, d, prompt, photos)


@app.post("/drawings/{drawing_id}/turns")
async def continue_drawing(user: User, drawing_id: str, request: Request) -> Drawing:
    prompt, photos = await _read_request(request)
    return await _turn(user, _load(user, drawing_id), prompt, photos)


@app.delete("/drawings/{drawing_id}", status_code=204)
def delete_drawing(user: User, drawing_id: str) -> None:
    """Removes the pictures, photos and conversation record. The codex thread
    itself stays in codex's own logs; nothing here points at it any more."""
    _load(user, drawing_id)
    shutil.rmtree(_user_dir(user) / drawing_id)


@app.get("/drawings/{drawing_id}/images/{name}")
def image(user: User, drawing_id: str, name: str) -> FileResponse:
    d = _load(user, drawing_id)
    if name not in d.images:
        raise HTTPException(404, "no such image")
    return FileResponse(_user_dir(user) / d.id / name, media_type="image/png")


@app.get("/drawings/{drawing_id}/photos/{name}")
def photo(user: User, drawing_id: str, name: str) -> FileResponse:
    """The person's own uploaded photo, so the app can show it in the conversation."""
    d = _load(user, drawing_id)
    if name not in d.photos:
        raise HTTPException(404, "no such photo")
    return FileResponse(_user_dir(user) / d.id / name)
