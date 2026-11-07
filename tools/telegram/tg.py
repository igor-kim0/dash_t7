"""Минимальный клиент Telegram Bot API на стандартной библиотеке.

Только то, что нужно боту канала: вызов метода JSON-ом, загрузка файлов
(фото/APK) multipart-ом, повторы при 429 и понятные ошибки. Токен берётся
из окружения TG_BOT_TOKEN; конкретный чат задаёт вызывающий код.
"""
from __future__ import annotations

import json
import mimetypes
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

API_ROOT = "https://api.telegram.org"


class TelegramError(RuntimeError):
    """Ответ Telegram с ok=false."""

    def __init__(self, method: str, description: str, code: int | None = None,
                 retry_after: int | None = None):
        self.method = method
        self.description = description
        self.code = code
        self.retry_after = retry_after
        super().__init__(f"{method}: {description} (code={code})")


class Telegram:
    def __init__(self, token: str | None = None, timeout: int = 40):
        token = token or os.environ.get("TG_BOT_TOKEN", "").strip()
        if not token:
            raise SystemExit(
                "Не задан TG_BOT_TOKEN. Создайте бота у @BotFather и положите "
                "токен в переменную окружения или секрет репозитория."
            )
        self.token = token
        self.timeout = timeout
        self._me: dict | None = None

    # -- низкий уровень -----------------------------------------------------
    def call(self, method: str, params: dict | None = None,
             files: dict[str, Path] | None = None) -> object:
        params = {k: v for k, v in (params or {}).items() if v is not None}
        url = f"{API_ROOT}/bot{self.token}/{method}"
        for attempt in range(3):
            try:
                data, headers = self._encode(params, files)
                req = urllib.request.Request(url, data=data, headers=headers)
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    payload = json.loads(resp.read().decode("utf-8"))
                break
            except urllib.error.HTTPError as exc:
                payload = self._read_error(exc)
                if exc.code == 429 and attempt < 2:
                    time.sleep(int(payload.get("parameters", {}).get("retry_after", 3)))
                    continue
                raise TelegramError(method, payload.get("description", str(exc)),
                                    payload.get("error_code", exc.code),
                                    payload.get("parameters", {}).get("retry_after"))
            except urllib.error.URLError as exc:
                if attempt < 2:
                    time.sleep(2 * (attempt + 1))
                    continue
                raise TelegramError(method, f"сеть недоступна: {exc.reason}") from exc

        if not payload.get("ok"):
            raise TelegramError(method, payload.get("description", "ok=false"),
                                payload.get("error_code"))
        return payload.get("result")

    def _encode(self, params: dict, files: dict[str, Path] | None):
        if not files:
            body = urllib.parse.urlencode(
                {k: self._as_text(v) for k, v in params.items()}
            ).encode()
            return body, {"Content-Type": "application/x-www-form-urlencoded"}

        boundary = uuid.uuid4().hex
        chunks: list[bytes] = []
        for key, value in params.items():
            chunks.append(
                f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"'
                f"\r\n\r\n{self._as_text(value)}\r\n".encode()
            )
        for key, path in files.items():
            ctype = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
            chunks.append(
                f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"; '
                f'filename="{path.name}"\r\nContent-Type: {ctype}\r\n\r\n'.encode()
            )
            chunks.append(path.read_bytes())
            chunks.append(b"\r\n")
        chunks.append(f"--{boundary}--\r\n".encode())
        return b"".join(chunks), {
            "Content-Type": f"multipart/form-data; boundary={boundary}"
        }

    @staticmethod
    def _as_text(value: object) -> str:
        if isinstance(value, bool):
            return "true" if value else "false"
        if isinstance(value, (dict, list)):
            return json.dumps(value, ensure_ascii=False)
        return str(value)

    @staticmethod
    def _read_error(exc: urllib.error.HTTPError) -> dict:
        try:
            return json.loads(exc.read().decode("utf-8"))
        except Exception:
            return {"description": str(exc), "error_code": exc.code}

    # -- удобные обёртки ----------------------------------------------------
    def me(self) -> dict:
        if self._me is None:
            self._me = self.call("getMe")
        return self._me

    def send_message(self, chat_id: str | int, text: str, *,
                     preview: bool = False, reply_to: int | None = None) -> dict:
        return self.call("sendMessage", {
            "chat_id": chat_id,
            "text": text,
            "parse_mode": "HTML",
            "link_preview_options": {"is_disabled": not preview},
            "reply_to_message_id": reply_to,
            "allow_sending_without_reply": True,
        })

    def send_photo(self, chat_id: str | int, path: Path, caption: str | None = None) -> dict:
        return self.call("sendPhoto", {
            "chat_id": chat_id,
            "caption": caption,
            "parse_mode": "HTML" if caption else None,
        }, {"photo": path})

    def send_document(self, chat_id: str | int, path: Path, caption: str | None = None) -> dict:
        return self.call("sendDocument", {
            "chat_id": chat_id,
            "caption": caption,
            "parse_mode": "HTML" if caption else None,
        }, {"document": path})

    def get_chat(self, chat_id: str | int) -> dict:
        return self.call("getChat", {"chat_id": chat_id})

    def get_member(self, chat_id: str | int, user_id: int) -> dict:
        return self.call("getChatMember", {"chat_id": chat_id, "user_id": user_id})
