"""Модерация чата обсуждений канала (и команд бота).

Бот должен быть администратором группы с правами «Удалять сообщения» и
«Блокировать участников». Он читает обновления через getUpdates (webhook не
используется), удаляет спам по правилам из moderation.json, после нескольких
замечаний ограничивает или блокирует автора, приветствует новичков и отвечает
на /help, /rules, /apk, /id.

Состояние (offset и счётчики нарушений) лежит в JSON в state-dir; в Actions
он сохраняется через actions/cache, локально — в tools/telegram/.state.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import re
import time
from pathlib import Path

import markdown
from tg import Telegram, TelegramError

DEFAULT_CONFIG: dict = {
    "spam_patterns": [
        r"казино", r"\bзайм", r"кредит наличными", r"заработок", r"инвестиц",
        r"криптовалют", r"\busdt\b", r"ставки на спорт", r"подписывайся",
        r"продам аккаунт", r"куплю аккаунт", r"работа на дому", r"\bp2p\b",
        r"быстрый доход", r"схема заработка", r"телеграм.?канал.*деньг",
    ],
    "max_links": 3,
    "mute_after": 2,
    "ban_after": 4,
    "mute_hours": 24,
    "delete_forwarded_from_channels": False,
    "welcome": (
        "👋 Добро пожаловать, {name}!\n\n"
        "Это обсуждение канала @tenet_t7_dash — дашборд для штатного ГУ "
        "Tenet T7. Вопросы по установке и настройке — сюда, баги и идеи — "
        "в issues на GitHub. Спам и реклама удаляются."
    ),
    "rules": (
        "📋 **Правила чата**\n\n"
        "- По делу: установка, настройка, баги дашборда.\n"
        "- Без рекламы, спама, крипты и «заработка».\n"
        "- Не публикуйте чужие персональные данные.\n"
        "- Для багов приложите версию, ГУ и лог — так поможем быстрее."
    ),
    "help": (
        "🤖 **Команды**\n\n"
        "- /apk — последний релиз и APK\n"
        "- /rules — правила чата\n"
        "- /id — ID этого чата\n"
        "- /help — эта справка"
    ),
}

LINK = re.compile(r"(?:https?://|t\.me/|www\.)\S+", re.IGNORECASE)
ANONYMOUS_ADMIN = 1087968824  # GroupAnonymousBot


def load_config(path: Path | None) -> dict:
    config = dict(DEFAULT_CONFIG)
    if path and path.exists():
        config.update(json.loads(path.read_text(encoding="utf-8")))
    config["_patterns"] = [re.compile(p, re.IGNORECASE)
                           for p in config.get("spam_patterns", [])]
    return config


def load_state(state_dir: Path) -> dict:
    path = state_dir / "moderation.json"
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {"offset": 0, "strikes": {}}


def save_state(state_dir: Path, state: dict) -> None:
    state_dir.mkdir(parents=True, exist_ok=True)
    (state_dir / "moderation.json").write_text(
        json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")


def _is_spam(message: dict, config: dict) -> str | None:
    text = message.get("text") or message.get("caption") or ""
    if not text:
        return None
    if config.get("delete_forwarded_from_channels") and \
            (message.get("forward_from_chat") or {}).get("type") == "channel":
        return "пересылка из канала"
    links = LINK.findall(text)
    if len(links) > int(config.get("max_links", 3)):
        return f"{len(links)} ссылок"
    for pattern in config["_patterns"]:
        if pattern.search(text):
            return f"шаблон {pattern.pattern!r}"
    return None


class Moderator:
    def __init__(self, tg: Telegram, chat_id: str | int, config: dict,
                 state: dict, *, dry_run: bool = False):
        self.tg = tg
        chat = tg.get_chat(chat_id)
        self.chat_id = chat["id"]
        self.chat_title = chat.get("title") or str(chat["id"])
        self.config = config
        self.state = state
        self.dry_run = dry_run
        self._admins: dict[int, bool] = {}
        self.me = tg.me()

    # -- вспомогательное ---------------------------------------------------
    def _admin(self, user_id: int) -> bool:
        if user_id == ANONYMOUS_ADMIN:
            return True
        if user_id not in self._admins:
            try:
                member = self.tg.get_member(self.chat_id, user_id)
                self._admins[user_id] = member.get("status") in ("creator", "administrator")
            except TelegramError:
                self._admins[user_id] = False
        return self._admins[user_id]

    def _reply(self, message: dict, text: str) -> None:
        if self.dry_run:
            print(f"[dry-run] ответ: {text}")
            return
        try:
            self.tg.send_message(self.chat_id, text,
                                 reply_to=message.get("message_id"))
        except TelegramError:
            self.tg.send_message(self.chat_id, text)

    def _delete(self, message: dict) -> bool:
        if self.dry_run:
            print(f"[dry-run] удалил бы сообщение {message.get('message_id')}")
            return True
        try:
            self.tg.call("deleteMessage", {
                "chat_id": self.chat_id,
                "message_id": message["message_id"],
            })
            return True
        except TelegramError as exc:
            if "message to delete not found" in exc.description:
                return True
            print(f"⚠️ не смог удалить сообщение: {exc}")
            return False

    def _strikes(self, user_id: int) -> int:
        key = f"{self.chat_id}:{user_id}"
        now = time.time()
        recent = [t for t in self.state["strikes"].get(key, []) if now - t < 7 * 86400]
        if recent:
            self.state["strikes"][key] = recent
        else:
            self.state["strikes"].pop(key, None)
        return len(recent)

    def _punish(self, message: dict, reason: str) -> None:
        user = message.get("from") or {}
        user_id = user.get("id")
        if not user_id:
            return
        if not self._delete(message):
            return
        key = f"{self.chat_id}:{user_id}"
        self.state["strikes"].setdefault(key, []).append(time.time())
        count = self._strikes(user_id)
        name = user.get("username") or user.get("first_name") or str(user_id)
        print(f"🧹 {name}: удалено ({reason}), нарушений за неделю: {count}")
        if self.dry_run:
            return
        try:
            if count >= int(self.config.get("ban_after", 4)):
                self.tg.call("banChatMember", {"chat_id": self.chat_id, "user_id": user_id})
                print(f"⛔ {name}: заблокирован")
            elif count >= int(self.config.get("mute_after", 2)):
                until = int(time.time()) + int(self.config.get("mute_hours", 24)) * 3600
                self.tg.call("restrictChatMember", {
                    "chat_id": self.chat_id,
                    "user_id": user_id,
                    "permissions": {"can_send_messages": False},
                    "until_date": until,
                })
                print(f"🔇 {name}: ограничен до {dt.datetime.fromtimestamp(until):%d.%m %H:%M}")
        except TelegramError as exc:
            print(f"⚠️ не смог ограничить {name}: {exc}")

    # -- команды -----------------------------------------------------------
    def _command(self, message: dict) -> bool:
        text = (message.get("text") or "").strip()
        if not text.startswith("/"):
            return False
        cmd = text.split()[0].split("@")[0].lower()
        if cmd in ("/help", "/start"):
            self._reply(message, markdown.join_blocks(
                markdown.parse_blocks(self.config["help"])))
        elif cmd == "/rules":
            self._reply(message, markdown.join_blocks(
                markdown.parse_blocks(self.config["rules"])))
        elif cmd == "/apk":
            repo = os.environ.get("GITHUB_REPOSITORY", "")
            url = f"https://github.com/{repo}/releases/latest" if repo else ""
            self._reply(message, "📦 <b>Последний релиз</b>\n" +
                        (f'<a href="{markdown.esc(url)}">{markdown.esc(url)}</a>'
                         if url else "Ссылка на релиз появится в канале."))
        elif cmd == "/id":
            self._reply(message, f"ID чата: <code>{self.chat_id}</code>")
        return True

    # -- обработка ---------------------------------------------------------
    def handle(self, update: dict) -> None:
        message = update.get("message")
        if not message or message.get("chat", {}).get("id") != self.chat_id:
            return
        if message.get("is_automatic_forward") or message.get("sender_chat"):
            return
        for member in message.get("new_chat_members", []):
            if member.get("is_bot"):
                continue
            text = self.config.get("welcome") or ""
            if text:
                name = (f"@{member['username']}" if member.get("username")
                        else markdown.esc(member.get("first_name") or "друг"))
                self._reply(message, markdown.join_blocks(
                    markdown.parse_blocks(text.replace("{name}", name))))
            return
        user = message.get("from") or {}
        if user.get("is_bot") or user.get("id") == self.me["id"]:
            return
        if self._command(message):
            return
        if user.get("id") and self._admin(user["id"]):
            return
        reason = _is_spam(message, self.config)
        if reason:
            self._punish(message, reason)

    def _chat_numeric(self) -> int | str:
        try:
            return int(self.chat_id)
        except (TypeError, ValueError):
            return str(self.chat_id)

    def poll(self) -> int:
        updates = self.tg.call("getUpdates", {
            "offset": int(self.state.get("offset", 0)),
            "timeout": 0,
            "allowed_updates": json.dumps(["message"]),
        }) or []
        handled = 0
        for update in updates:
            try:
                self.handle(update)
            except Exception as exc:  # один кривой апдейт не должен ронять прогон
                print(f"⚠️ апдейт {update.get('update_id')}: {exc}")
            finally:
                self.state["offset"] = max(
                    int(self.state.get("offset", 0)),
                    int(update["update_id"]) + 1)
            handled += 1
        if updates:
            print(f"Обработано обновлений: {len(updates)}")
        return handled
