"""Текст анонса релиза из CHANGELOG и загрузка APK из GitHub Release."""
from __future__ import annotations

import datetime as dt
import json
import os
import re
import subprocess
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

import markdown

MONTHS = ("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
          "августа", "сентября", "октября", "ноября", "декабря")

SECTION = re.compile(r"^## \[([^\]]+)\](?:\s*[—–-]\s*(\d{4}-\d{2}-\d{2}))?\s*$")


def version_from_tag(tag: str) -> str:
    ver = tag.strip()
    if ver.startswith("v"):
        ver = ver[1:]
    if ver.endswith("-dev"):
        ver = ver[:-4]
    return ver


def repo_slug(explicit: str | None = None) -> str:
    if explicit:
        return explicit.strip("/")
    env = os.environ.get("GITHUB_REPOSITORY")
    if env:
        return env
    try:
        url = subprocess.check_output(
            ["git", "remote", "get-url", "origin"], text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except Exception:
        return ""
    m = re.search(r"github\.com[:/]([^/]+/[^/.]+)", url)
    return m.group(1) if m else ""


def release_url(repo: str, tag: str) -> str:
    return f"https://github.com/{repo}/releases/tag/{tag}"


def find_section(changelog: str, version: str) -> tuple[str, str] | None:
    """Возвращает (дата, markdown раздела) для версии из CHANGELOG."""
    lines = changelog.splitlines()
    start = None
    date = ""
    for i, line in enumerate(lines):
        m = SECTION.match(line)
        if m:
            if start is not None:
                return date, "\n".join(lines[start:i]).strip()
            if m.group(1) == version:
                start = i + 1
                date = m.group(2) or ""
    if start is not None:
        return date, "\n".join(lines[start:]).strip()
    return None


def human_date(iso: str) -> str:
    try:
        d = dt.date.fromisoformat(iso)
    except ValueError:
        return ""
    return f"{d.day} {MONTHS[d.month - 1]} {d.year}"


def build_post(*, tag: str, repo: str, changelog: str, prerelease: bool = False,
               apk_url: str | None = None, full: bool = False,
               limit: int = markdown.SAFE_LIMIT) -> tuple[str, bool]:
    """Собирает HTML-сообщение анонса. Возвращает (html, обрезано ли).

    По умолчанию — короткий пост: заголовок и ссылки, а список изменений
    читается в GitHub Release (раздел CHANGELOG целиком в канал не влезает).
    full=True — прежний вид: раздел CHANGELOG, обрезанный под лимит.
    apk_url — прямая ссылка на APK в GitHub Release; без неё — ссылка на
    страницу релиза.
    """
    version = version_from_tag(tag)
    found = find_section(changelog, version)
    if not found:
        raise ValueError(f"В CHANGELOG.md нет раздела для версии {version}")
    date, section = found
    if not section:
        raise ValueError(f"Раздел [{version}] в CHANGELOG.md пуст")

    title = f"🚗 <b>DashHeadUnit {markdown.esc(version)}</b>"
    if date:
        title += f"\nОбновление от {human_date(date)}"
    if prerelease:
        title += " · тестовая сборка"

    url = release_url(repo, tag) if repo else ""
    footer = ""
    if url and apk_url:
        footer = (f'📦 <a href="{markdown.esc(apk_url)}">Скачать APK</a> · '
                  f'<a href="{markdown.esc(url)}">релиз на GitHub</a>')
    elif url:
        footer = (f'📦 <a href="{markdown.esc(url)}">Скачать APK и полный '
                  f"список изменений</a>")
    if not full:
        lines = [title, ""]
        if url:
            lines.append(f'📝 <a href="{markdown.esc(url)}">Что нового — список изменений на GitHub</a>')
        if apk_url:
            lines.append(f'📦 <a href="{markdown.esc(apk_url)}">Скачать APK</a>')
        return "\n".join(lines).rstrip(), False
    blocks = markdown.parse_blocks(section)
    return markdown.fit_blocks(blocks, header=title, footer=footer, limit=limit)


# -- GitHub API -------------------------------------------------------------

def _github_json(url: str, token: str | None) -> dict:
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "tenet-t7-dash-bot",
        **({"Authorization": f"Bearer {token}"} if token else {}),
    })
    with urllib.request.urlopen(req, timeout=40) as resp:
        return json.loads(resp.read().decode("utf-8"))


def release_body(repo: str, tag: str, token: str | None) -> str:
    data = _github_json(f"https://api.github.com/repos/{repo}/releases/tags/{tag}", token)
    return data.get("body") or ""


def _apk_asset(repo: str, tag: str, token: str | None) -> dict | None:
    release = _github_json(f"https://api.github.com/repos/{repo}/releases/tags/{tag}", token)
    return next((a for a in release.get("assets", [])
                 if a.get("name", "").endswith(".apk")), None)


def apk_download_url(repo: str, tag: str, token: str | None) -> str | None:
    """Прямая ссылка на APK из релиза (None — нет репозитория/релиза/APK)."""
    if not repo:
        return None
    try:
        asset = _apk_asset(repo, tag, token)
    except Exception as exc:
        print(f"⚠️ Ссылка на APK не получена: {exc}")
        return None
    return asset.get("browser_download_url") if asset else None


def download_apk(repo: str, tag: str, token: str | None,
                 max_bytes: int = 49 * 1024 * 1024) -> Path | None:
    """Скачивает APK из релиза во временный файл (None — если нет/велик)."""
    if not repo:
        return None
    asset = _apk_asset(repo, tag, token)
    if not asset:
        return None
    if int(asset.get("size", 0)) > max_bytes:
        print(f"APK {asset['size'] / 1024 / 1024:.1f} МБ — больше лимита бота, "
              f"прикреплять не буду")
        return None
    req = urllib.request.Request(asset["url"], headers={
        "Accept": "application/octet-stream",
        "User-Agent": "tenet-t7-dash-bot",
        **({"Authorization": f"Bearer {token}"} if token else {}),
    })
    tmp = Path(tempfile.mkdtemp(prefix="tg-apk-")) / asset["name"]
    with urllib.request.urlopen(req, timeout=120) as resp, tmp.open("wb") as out:
        while chunk := resp.read(1024 * 256):
            out.write(chunk)
    return tmp
