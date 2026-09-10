import shutil
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

from elisart import codex
from elisart.settings import Settings

settings = Settings()


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    settings.prepare_workspace()
    yield


app = FastAPI(title="elisart", lifespan=lifespan)


def require_token(authorization: Annotated[str | None, Header()] = None) -> None:
    if authorization != f"Bearer {settings.token}":
        raise HTTPException(401, "bad token")


Auth = Depends(require_token)


class Prompt(BaseModel):
    prompt: str


class Drawing(BaseModel):
    id: str
    thread_id: str | None
    text: str
    images: list[str]
    error: str | None = None


def _meta_path(drawing_id: str) -> Path:
    return settings.drawings_dir / drawing_id / "meta.json"


def _load(drawing_id: str) -> Drawing:
    path = _meta_path(drawing_id)
    if not path.exists():
        raise HTTPException(404, "no such drawing")
    return Drawing.model_validate_json(path.read_text())


def _save(d: Drawing) -> None:
    path = _meta_path(d.id)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(d.model_dump_json(indent=2))


@app.get("/health")
def health() -> dict:
    return {"ok": True}


@app.get("/drawings", dependencies=[Auth])
def list_drawings() -> list[Drawing]:
    if not settings.drawings_dir.is_dir():
        return []
    items = [_load(p.name) for p in settings.drawings_dir.iterdir() if (p / "meta.json").exists()]
    return sorted(items, key=lambda d: _meta_path(d.id).stat().st_mtime, reverse=True)


async def _turn(d: Drawing, prompt: str) -> Drawing:
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
    dest = settings.drawings_dir / d.id
    dest.mkdir(parents=True, exist_ok=True)
    for src in result.images:
        name = f"{len(d.images) + 1:03d}.png"
        shutil.copy2(src, dest / name)
        d.images.append(name)
    _save(d)
    if d.error and not result.images:
        raise HTTPException(502, d.error)
    return d


@app.post("/drawings", dependencies=[Auth])
async def new_drawing(body: Prompt) -> Drawing:
    d = Drawing(id=uuid.uuid4().hex[:12], thread_id=None, text="", images=[])
    return await _turn(d, body.prompt)


@app.post("/drawings/{drawing_id}/turns", dependencies=[Auth])
async def continue_drawing(drawing_id: str, body: Prompt) -> Drawing:
    return await _turn(_load(drawing_id), body.prompt)


@app.get("/drawings/{drawing_id}/images/{name}", dependencies=[Auth])
def image(drawing_id: str, name: str) -> FileResponse:
    d = _load(drawing_id)
    if name not in d.images:
        raise HTTPException(404, "no such image")
    return FileResponse(settings.drawings_dir / d.id / name, media_type="image/png")
