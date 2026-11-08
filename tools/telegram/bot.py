#!/usr/bin/env python3
"""Бот Telegram-канала @tenet_t7_dash.

Команды:

    bot.py post-release --tag v1.8      # анонс релиза из CHANGELOG (+ APK)
    bot.py run-queue                    # отложенные посты из content/telegram/queue
    bot.py queue-wait                   # сколько секунд ждать ближайший пост
    bot.py moderate                     # спам, приветствия, команды в чате обсуждений
    bot.py check                        # проверить токен, канал и права бота
    bot.py chat-id @some_group          # узнать ID чата для секретов
    bot.py webhook --delete             # снять webhook, чтобы работал getUpdates

Токен — в TG_BOT_TOKEN. Канал — в TG_CHANNEL_ID (по умолчанию
@tenet_t7_dash), чат обсуждений — в TG_DISCUSSION_CHAT_ID. Подробная
настройка: docs/telegram-bot.md.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
from pathlib import Path

import markdown
import moderation
import postqueue as queue
import releases
from tg import Telegram, TelegramError

DEFAULT_CHANNEL = "@tenet_t7_dash"
STATE_DIR = Path(os.environ.get("TG_STATE_DIR", Path(__file__).parent / ".state"))


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def channel_id(arg: str | None) -> str:
    return arg or env("TG_CHANNEL_ID", DEFAULT_CHANNEL)


def cmd_post_release(args: argparse.Namespace) -> int:
    repo = releases.repo_slug(args.repo)
    tag = args.tag
    prerelease = args.prerelease or tag.endswith("-dev")
    changelog_path = Path(args.changelog)
    if not changelog_path.exists():
        print(f"Нет {changelog_path}")
        return 2
    changelog = changelog_path.read_text(encoding="utf-8")
    try:
        html, truncated = releases.build_post(
            tag=tag, repo=repo, changelog=changelog, prerelease=prerelease)
    except ValueError as exc:
        print(f"⚠️ {exc}")
        if not repo or args.no_release_body:
            return 2
        try:
            body = releases.release_body(repo, tag, env("GITHUB_TOKEN") or None)
        except Exception as exc:
            print(f"❌ Не удалось взять текст из GitHub Release: {exc}")
            return 2
        version = releases.version_from_tag(tag)
        fake = f"## [{version}]\n\n{body}\n"
        html, truncated = releases.build_post(
            tag=tag, repo=repo, changelog=fake, prerelease=prerelease)

    version = releases.version_from_tag(tag)
    print(f"Пост для {tag}: {markdown.visible_len(html)} символов"
          f"{' (обрезан)' if truncated else ''}")
    if args.dry_run:
        print("--- сообщение ---")
        print(html)
        print("--- конец ---")
        return 0

    tg = Telegram()
    chat = channel_id(args.channel)
    tg.send_message(chat, html)
    print(f"✅ Анонс {version} опубликован в {chat}")

    if args.no_apk:
        return 0
    try:
        apk = releases.download_apk(repo, tag, env("GITHUB_TOKEN") or None)
    except Exception as exc:
        print(f"⚠️ APK не скачался: {exc}")
        return 0
    if not apk:
        return 0
    size = apk.stat().st_size / 1024 / 1024
    caption = f"📦 APK {markdown.esc(version)} · {size:.1f} МБ"
    tg.send_document(chat, apk, caption)
    print(f"✅ APK прикреплён ({size:.1f} МБ)")
    return 0


def cmd_run_queue(args: argparse.Namespace) -> int:
    now = None
    if args.now:
        now = queue.parse_date(args.now)
    tg = None if args.dry_run else Telegram()
    sent, waiting = queue.run_queue(
        tg=tg,
        chat_id=channel_id(args.channel),
        queue_dir=Path(args.queue_dir),
        sent_dir=Path(args.sent_dir),
        now=now,
        dry_run=args.dry_run,
    )
    print(f"Итого: отправлено {sent}, ждут {waiting}")
    return 0


def cmd_queue_wait(args: argparse.Namespace) -> int:
    now = queue.parse_date(args.now) if args.now else dt.datetime.now(dt.timezone.utc)
    due = queue.next_due(Path(args.queue_dir), now, future_only=args.future_only)
    if due is None:
        print("wait=none")
    else:
        print(f"wait={max(0, int((due - now).total_seconds()))}")
    return 0


def cmd_moderate(args: argparse.Namespace) -> int:
    chat = args.chat_id or env("TG_DISCUSSION_CHAT_ID")
    if not chat:
        print("ℹ️ TG_DISCUSSION_CHAT_ID не задан — модерация выключена")
        return 0
    state_dir = Path(args.state_dir)
    config = moderation.load_config(Path(args.config) if args.config else None)
    state = moderation.load_state(state_dir)
    tg = Telegram()
    mod = moderation.Moderator(tg, chat, config, state, dry_run=args.dry_run)
    if not args.dry_run:
        try:
            member = tg.get_member(mod.chat_id, tg.me()["id"])
            rights = ("админ" if member.get("status") in ("creator", "administrator")
                      else "НЕ админ")
            print(f"Чат: {mod.chat_title} ({mod.chat_id}), бот: {rights}")
        except TelegramError as exc:
            print(f"⚠️ Не удалось проверить права: {exc}")
    try:
        mod.poll()
    finally:
        if not args.dry_run:
            moderation.save_state(state_dir, state)
    return 0


def cmd_check(args: argparse.Namespace) -> int:
    tg = Telegram()
    me = tg.me()
    print(f"✅ Бот: @{me.get('username')} (id {me.get('id')})")
    ok = True
    chat = channel_id(args.channel)
    try:
        info = tg.get_chat(chat)
        print(f"✅ Канал: {info.get('title')} ({info.get('id')}, @{info.get('username')})")
        member = tg.get_member(info["id"], me["id"])
        can_post = member.get("status") == "creator" or member.get("can_post_messages")
        print(f"{'✅' if can_post else '❌'} Право публикации: "
              f"{'есть' if can_post else 'нет — сделайте бота админом канала'}")
        ok = ok and bool(can_post)
    except TelegramError as exc:
        print(f"❌ Канал {chat}: {exc}")
        ok = False

    group = args.chat_id or env("TG_DISCUSSION_CHAT_ID")
    if group:
        try:
            info = tg.get_chat(group)
            member = tg.get_member(info["id"], me["id"])
            rights = member.get("status") in ("creator", "administrator")
            can_delete = rights and member.get("can_delete_messages")
            can_restrict = rights and member.get("can_restrict_members")
            print(f"{'✅' if rights else '❌'} Чат обсуждений: {info.get('title')} "
                  f"({info.get('id')})")
            print(f"{'✅' if can_delete else '❌'} Удаление сообщений")
            print(f"{'✅' if can_restrict else '❌'} Блокировка участников")
            ok = ok and rights and bool(can_delete) and bool(can_restrict)
        except TelegramError as exc:
            print(f"❌ Чат обсуждений {group}: {exc}")
            ok = False
    else:
        print("ℹ️ TG_DISCUSSION_CHAT_ID не задан — проверен только канал")

    try:
        info = tg.call("getWebhookInfo")
        if info.get("url"):
            print(f"⚠️ Включён webhook {info['url']} — getUpdates для модерации "
                  f"не работает. Выполните: bot.py webhook --delete")
            ok = False
        else:
            print("✅ Webhook выключен (getUpdates доступен)")
    except TelegramError as exc:
        print(f"⚠️ getWebhookInfo: {exc}")
    return 0 if ok else 1


def cmd_chat_id(args: argparse.Namespace) -> int:
    tg = Telegram()
    info = tg.get_chat(args.username)
    print(f"{info.get('title') or info.get('username')}: {info['id']}")
    return 0


def cmd_chats(args: argparse.Namespace) -> int:
    """Показывает чаты из последних обновлений — чтобы узнать ID группы."""
    tg = Telegram()
    updates = tg.call("getUpdates", {
        "timeout": 0,
        "allowed_updates": json.dumps(["message", "channel_post", "my_chat_member"]),
    }) or []
    seen: dict[int, dict] = {}
    last = 0
    for update in updates:
        last = max(last, int(update.get("update_id", 0)))
        for key in ("message", "channel_post", "my_chat_member", "edited_message"):
            obj = update.get(key) or {}
            chat = obj.get("chat") or {}
            if chat.get("id") is not None:
                seen[chat["id"]] = chat
    if not seen:
        print("Обновлений нет. Напишите что-нибудь в группе (бот должен быть "
              "её участником), затем повторите команду.")
        return 0
    for chat in seen.values():
        title = chat.get("title") or chat.get("username") or chat.get("first_name", "")
        print(f"{chat['id']:>15}  {chat.get('type', '?'):<10}  {title}")
    if args.confirm:
        tg.call("getUpdates", {"offset": last + 1, "timeout": 0})
        print("Обновления подтверждены (offset сдвинут)")
    else:
        print("Обновления не подтверждены — модерация их ещё увидит")
    return 0


def cmd_webhook(args: argparse.Namespace) -> int:
    tg = Telegram()
    if args.delete:
        tg.call("deleteWebhook", {"drop_pending_updates": False})
        print("✅ Webhook снят, getUpdates снова работает")
    else:
        print(tg.call("getWebhookInfo"))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("post-release", help="анонс релиза из CHANGELOG")
    p.add_argument("--tag", required=True, help="тег релиза, например v1.8")
    p.add_argument("--repo", help="owner/repo (по умолчанию из git/GitHub)")
    p.add_argument("--changelog", default="CHANGELOG.md")
    p.add_argument("--channel", help="канал (по умолчанию TG_CHANNEL_ID)")
    p.add_argument("--prerelease", action="store_true", help="пометить как тестовую сборку")
    p.add_argument("--no-apk", action="store_true", help="не прикреплять APK")
    p.add_argument("--no-release-body", action="store_true",
                   help="не брать текст из GitHub Release, если нет раздела в CHANGELOG")
    p.add_argument("--dry-run", action="store_true")
    p.set_defaults(func=cmd_post_release)

    p = sub.add_parser("run-queue", help="опубликовать созревшие посты")
    p.add_argument("--queue-dir", default="content/telegram/queue")
    p.add_argument("--sent-dir", default="content/telegram/sent")
    p.add_argument("--channel", help="канал (по умолчанию TG_CHANNEL_ID)")
    p.add_argument("--now", help="проверить очередь на эту дату (ISO 8601)")
    p.add_argument("--dry-run", action="store_true")
    p.set_defaults(func=cmd_run_queue)

    p = sub.add_parser("queue-wait",
                       help="секунд до ближайшего поста: wait=N или wait=none")
    p.add_argument("--queue-dir", default="content/telegram/queue")
    p.add_argument("--now", help="считать от этой даты (ISO 8601)")
    p.add_argument("--future-only", action="store_true",
                   help="не учитывать посты, время которых уже наступило")
    p.set_defaults(func=cmd_queue_wait)

    p = sub.add_parser("moderate", help="один проход модерации чата")
    p.add_argument("--chat-id", help="чат обсуждений (по умолчанию TG_DISCUSSION_CHAT_ID)")
    p.add_argument("--config", default=str(Path(__file__).parent / "moderation.json"))
    p.add_argument("--state-dir", default=str(STATE_DIR))
    p.add_argument("--dry-run", action="store_true")
    p.set_defaults(func=cmd_moderate)

    p = sub.add_parser("check", help="проверить токен, канал и права")
    p.add_argument("--channel", help="канал (по умолчанию TG_CHANNEL_ID)")
    p.add_argument("--chat-id", help="чат обсуждений (по умолчанию TG_DISCUSSION_CHAT_ID)")
    p.set_defaults(func=cmd_check)

    p = sub.add_parser("chat-id", help="узнать ID чата по @username")
    p.add_argument("username")
    p.set_defaults(func=cmd_chat_id)

    p = sub.add_parser("chats", help="чаты из последних обновлений (ID группы)")
    p.add_argument("--confirm", action="store_true",
                   help="подтвердить обновления (модерация их уже не увидит)")
    p.set_defaults(func=cmd_chats)

    p = sub.add_parser("webhook", help="информация о webhook или удаление")
    p.add_argument("--delete", action="store_true")
    p.set_defaults(func=cmd_webhook)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except TelegramError as exc:
        print(f"❌ Telegram: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
