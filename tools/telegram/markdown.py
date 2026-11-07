"""Markdown → HTML для Telegram и раскладка текста по лимиту сообщения.

Telegram понимает только свой поднабор HTML, поэтому конвертируем аккуратно:
сначала экранируем текст, потом расставляем теги. Поддерживаются заголовки,
списки, **жирный**, `код` и ссылки [текст](url) — этого хватает CHANGELOG
и постов из очереди.
"""
from __future__ import annotations

import html
import re

LIMIT = 4096          # жёсткий лимит Telegram на текст сообщения
SAFE_LIMIT = 3900     # оставляем запас на сущности и подпись

_INLINE = re.compile(
    r"`([^`\n]+)`"
    r"|\*\*([^*\n]+)\*\*"
    r"|\[([^\]\n]+)\]\((https?://[^)\s]+)\)"
)
_TAG = re.compile(r"<[^>]+>")


def esc(text: str) -> str:
    return (text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace('"', "&quot;"))


def inline(text: str) -> str:
    """Экранирует текст и превращает простую разметку в HTML Telegram."""
    out: list[str] = []
    pos = 0
    for m in _INLINE.finditer(text):
        out.append(esc(text[pos:m.start()]))
        code, bold, link_text, link_url = m.groups()
        if code is not None:
            out.append(f"<code>{esc(code)}</code>")
        elif bold is not None:
            out.append(f"<b>{esc(bold)}</b>")
        else:
            out.append(f'<a href="{esc(link_url)}">{esc(link_text)}</a>')
        pos = m.end()
    out.append(esc(text[pos:]))
    return "".join(out)


def visible_len(markup: str) -> int:
    """Длина текста, которую увидит читатель (без HTML-тегов)."""
    return len(html.unescape(_TAG.sub("", markup)))


def _cut_raw(text: str, limit: int) -> tuple[str, str]:
    """Делит текст по границе слова.

    Возвращает (кусок, остаток). Остаток пуст, если текст поместился целиком.
    В конце куска ставится многоточие, если что-то осталось.
    """
    if limit <= 0:
        return "…", text
    if len(text) <= limit:
        return text, ""
    cut = text[:limit]
    space = cut.rfind(" ")
    if space > limit // 2:
        cut = cut[:space]
    cut = cut.rstrip(" ,.;:—-")
    return cut + "…", text[len(cut):].lstrip(" ,.;:—-")


def parse_blocks(markdown: str) -> list[tuple[str, str]]:
    """Разбирает markdown на блоки (kind, text): h, li, p.

    Строки без пустой строки между ними считаются продолжением: CHANGELOG
    переносит длинные пункты по 80 колонкам, и склеивать их обратно в абзац
    важнее, чем сохранить исходные переносы.
    """
    blocks: list[tuple[str, str]] = []
    current: tuple[str, str] | None = None

    def flush() -> None:
        nonlocal current
        if current is not None:
            kind, text = current
            text = re.sub(r"\s+", " ", text).strip()
            if text:
                blocks.append((kind, text))
            current = None

    for raw in markdown.splitlines():
        line = raw.rstrip()
        if not line.strip():
            flush()
            continue
        if line.startswith("### "):
            flush()
            blocks.append(("h", line[4:].strip()))
        elif line.startswith("## "):
            flush()
            blocks.append(("h", line[3:].strip()))
        elif line.startswith("# "):
            flush()
            blocks.append(("h", line[2:].strip()))
        elif line.startswith(("- ", "* ")):
            flush()
            current = ("li", line[2:].strip())
        elif current is not None and not line.startswith("#"):
            kind, text = current
            current = (kind, f"{text} {line.strip()}")
        else:
            flush()
            current = ("p", line.strip())
    flush()
    return blocks


def render_block(block: tuple[str, str]) -> str:
    kind, text = block
    if kind == "h":
        return f"<b>{inline(text)}</b>"
    if kind == "li":
        return f"• {inline(text)}"
    return inline(text)


def _separator(prev: str | None, kind: str) -> int:
    """Длина разделителя между блоками: пункты списка идут подряд."""
    if prev is None:
        return 0
    return 1 if prev == "li" and kind == "li" else 2


def join_blocks(blocks: list[tuple[str, str]]) -> str:
    out: list[str] = []
    prev: str | None = None
    for block in blocks:
        out.append("\n" * _separator(prev, block[0]))
        out.append(render_block(block))
        prev = block[0]
    return "".join(out)


def fit_blocks(blocks: list[tuple[str, str]], *, header: str = "",
               footer: str = "", limit: int = SAFE_LIMIT) -> tuple[str, bool]:
    """Собирает одно сообщение не длиннее limit.

    Возвращает (html, обрезано ли). Если блоки не влезают, последний режется
    по границе слова — лучше показать начало и ссылку, чем ничего.
    """
    if limit < 64:
        raise ValueError("limit слишком мал для сообщения")
    parts: list[str] = []
    used = visible_len(header) + visible_len(footer)
    truncated = False
    prev: str | None = None
    for block in blocks:
        rendered = render_block(block)
        sep = _separator(prev, block[0])
        need = visible_len(rendered) + sep
        if used + need <= limit:
            parts.append("\n" * sep + rendered)
            used += need
            prev = block[0]
            continue
        room = limit - used - sep - 1
        if room > 60:
            cut, _rest = _cut_raw(block[1], max(room - 2, 0))
            parts.append("\n" * sep + render_block((block[0], cut)))
        truncated = True
        break

    body = "".join(parts)
    if header:
        body = f"{header}\n\n{body}" if body else header
    if footer:
        body = f"{body}\n\n{footer}" if body else footer
    return body, truncated


def split_blocks(blocks: list[tuple[str, str]], *, limit: int = SAFE_LIMIT) -> list[str]:
    """Раскладывает блоки на несколько сообщений не длиннее limit."""
    if limit < 64:
        raise ValueError("limit слишком мал для сообщения")
    messages: list[str] = []
    current: list[str] = []
    used = 0
    prev: str | None = None

    def flush() -> None:
        nonlocal current, used, prev
        if current:
            messages.append("".join(current))
        current, used, prev = [], 0, None

    for block in blocks:
        rendered = render_block(block)
        sep = _separator(prev, block[0])
        need = visible_len(rendered) + sep
        if used + need <= limit:
            current.append("\n" * sep + rendered)
            used += need
            prev = block[0]
            continue
        flush()
        # Блок сам по себе длиннее сообщения — режем на куски.
        text = block[1]
        while text:
            piece, text = _cut_raw(text, limit - 2)
            messages.append(render_block((block[0], piece)))
    flush()
    return messages
