"""Очередь отложенных постов: content/telegram/queue/*.md.

Формат файла:

    ---
    publish_at: 2026-11-10T09:00:00+03:00
    photo: docs/screenshots/dashboard.png   # необязательно
    ---
    Текст поста в **Markdown**.

Опубликованные файлы переносятся в content/telegram/sent/ — это и есть
отметка «отправлено», её коммитит workflow.
"""
from __future__ import annotations

import datetime as dt
import shutil
from pathlib import Path

import markdown
from tg import Telegram

CAPTION_LIMIT = 1000


def parse_front_matter(text: str) -> tuple[dict[str, str], str]:
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return {}, text
    meta: dict[str, str] = {}
    for i, line in enumerate(lines[1:], start=1):
        if line.strip() == "---":
            return meta, "\n".join(lines[i + 1:]).lstrip("\n")
        if ":" in line and not line.lstrip().startswith("#"):
            key, value = line.split(":", 1)
            meta[key.strip().lower()] = value.split(" #", 1)[0].strip().strip('"\'')
    return {}, text  # незакрытый front matter — считаем обычным текстом


def parse_date(value: str) -> dt.datetime:
    raw = value.strip()
    if raw.endswith("Z"):
        raw = raw[:-1] + "+00:00"
    try:
        parsed = dt.datetime.fromisoformat(raw)
    except ValueError as exc:
        raise ValueError(f"непонятная дата publish_at: {value!r}") from exc
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed


def load_post(path: Path) -> dict:
    meta, body = parse_front_matter(path.read_text(encoding="utf-8"))
    return {
        "path": path,
        "meta": meta,
        "text": body.strip(),
        "publish_at": parse_date(meta["publish_at"]) if meta.get("publish_at") else None,
        "photo": Path(meta["photo"]) if meta.get("photo") else None,
    }


def publish_post(tg: Telegram, chat_id: str | int, post: dict, *,
                 dry_run: bool = False) -> None:
    text = post["text"]
    if not text:
        raise ValueError(f"{post['path']}: пустой текст поста")
    blocks = markdown.parse_blocks(text)
    photo: Path | None = post["photo"]
    if photo and not photo.exists():
        raise FileNotFoundError(f"{post['path']}: нет файла photo={photo}")

    if photo:
        preview = markdown.join_blocks(blocks)
        caption = preview if markdown.visible_len(preview) <= CAPTION_LIMIT else None
        if dry_run:
            print(f"[dry-run] фото {photo}, подпись: {caption or '—'}")
            if caption is None:
                for msg in markdown.split_blocks(blocks):
                    print(f"[dry-run] текст ({len(msg)}):\n{msg}")
            return
        tg.send_photo(chat_id, photo, caption)
        if caption is None:
            for msg in markdown.split_blocks(blocks):
                tg.send_message(chat_id, msg)
        return

    for msg in markdown.split_blocks(blocks):
        if dry_run:
            print(f"[dry-run] текст ({len(msg)}):\n{msg}")
        else:
            tg.send_message(chat_id, msg)


def run_queue(*, tg: Telegram | None, chat_id: str | int, queue_dir: Path,
              sent_dir: Path, now: dt.datetime | None = None,
              dry_run: bool = False) -> tuple[int, int]:
    """Публикует созревшие посты. Возвращает (отправлено, отложено)."""
    now = now or dt.datetime.now(dt.timezone.utc)
    if not queue_dir.exists():
        print(f"Очередь пуста: нет каталога {queue_dir}")
        return 0, 0
    posts: list[dict] = []
    waiting = 0
    for path in queue_dir.glob("*.md"):
        try:
            posts.append(load_post(path))
        except Exception as exc:
            print(f"⚠️ {path.name}: {exc}")
            waiting += 1
    posts.sort(key=lambda p: (p["publish_at"] or now, p["path"].name))
    sent = 0
    for post in posts:
        path = post["path"]
        if post["publish_at"] and post["publish_at"] > now:
            print(f"⏳ {path.name}: ждёт до {post['publish_at'].isoformat()}")
            waiting += 1
            continue
        print(f"📤 {path.name}: публикую")
        try:
            publish_post(tg, chat_id, post, dry_run=dry_run)  # type: ignore[arg-type]
        except Exception as exc:
            print(f"❌ {path.name}: {exc}")
            waiting += 1
            continue
        sent += 1
        if not dry_run:
            sent_dir.mkdir(parents=True, exist_ok=True)
            target = sent_dir / path.name
            if target.exists():
                target = sent_dir / f"{path.stem}-{int(now.timestamp())}{path.suffix}"
            shutil.move(str(path), target)
    return sent, waiting


def next_due(queue_dir: Path, now: dt.datetime | None = None, *,
             future_only: bool = False) -> dt.datetime | None:
    """Когда публиковать ближайший пост из очереди.

    Пост без даты или с наступившей датой — это ``now``. С ``future_only``
    такие посты пропускаются: так цепочка ожидания не зацикливается на посте,
    который не удаётся отправить. Пустая очередь даёт ``None``.
    """
    now = now or dt.datetime.now(dt.timezone.utc)
    if not queue_dir.exists():
        return None
    best: dt.datetime | None = None
    for path in queue_dir.glob("*.md"):
        try:
            at = load_post(path)["publish_at"] or now
        except Exception:
            continue
        if at <= now:
            if future_only:
                continue
            at = now
        if best is None or at < best:
            best = at
    return best
