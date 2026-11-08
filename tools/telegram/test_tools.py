#!/usr/bin/env python3
"""Быстрые проверки бота без сети: python3 tools/telegram/test_tools.py."""
from __future__ import annotations

import datetime as dt
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import markdown  # noqa: E402
import moderation  # noqa: E402
import postqueue as queue  # noqa: E402
import releases  # noqa: E402

CHANGELOG = """# Changelog

## [Unreleased]

## [1.9] — 2026-12-01

### Добавлено
- Первое изменение с [ссылкой](https://example.com/a?x=1&y=2) и `кодом`,
  которое занимает несколько строк.
- Второе изменение.

## [1.8] — 2026-11-04

### Исправлено
- Что-то старое.
"""


class InlineTest(unittest.TestCase):
    def test_escapes_html(self):
        self.assertEqual(markdown.inline("a < b & c > d"), "a &lt; b &amp; c &gt; d")

    def test_links_code_bold(self):
        out = markdown.inline("**важно** и `x<y` — [сайт](https://e.com/?a=1&b=2)")
        self.assertIn("<b>важно</b>", out)
        self.assertIn("<code>x&lt;y</code>", out)
        self.assertIn('<a href="https://e.com/?a=1&amp;b=2">сайт</a>', out)

    def test_visible_len_ignores_tags(self):
        self.assertEqual(markdown.visible_len("<b>привет</b>"), 6)


class BlocksTest(unittest.TestCase):
    def test_wrapped_lines_join(self):
        blocks = markdown.parse_blocks("- строка один\n  строка два\n- вторая")
        self.assertEqual(blocks, [("li", "строка один строка два"), ("li", "вторая")])

    def test_headings(self):
        blocks = markdown.parse_blocks("### Добавлено\n- пункт")
        self.assertEqual(blocks, [("h", "Добавлено"), ("li", "пункт")])

    def test_fit_truncates_and_respects_limit(self):
        blocks = [("li", "очень длинный пункт " * 60)]
        out, truncated = markdown.fit_blocks(blocks, header="Заголовок", limit=500)
        self.assertTrue(truncated)
        self.assertLessEqual(markdown.visible_len(out), 500)

    def test_split_keeps_all_words(self):
        blocks = [("p", " ".join(f"слово{i}" for i in range(400)))]
        parts = markdown.split_blocks(blocks, limit=300)
        self.assertGreater(len(parts), 1)
        self.assertTrue(all(markdown.visible_len(p) <= 300 for p in parts))
        joined = " ".join(parts)
        self.assertIn("слово0", joined)
        self.assertIn("слово399", joined)


class ReleaseTest(unittest.TestCase):
    def test_version_from_tag(self):
        self.assertEqual(releases.version_from_tag("v1.9-dev"), "1.9")
        self.assertEqual(releases.version_from_tag("1.9"), "1.9")

    def test_find_section(self):
        date, body = releases.find_section(CHANGELOG, "1.9")
        self.assertEqual(date, "2026-12-01")
        self.assertIn("Первое изменение", body)
        self.assertNotIn("старое", body)

    def test_missing_section(self):
        self.assertIsNone(releases.find_section(CHANGELOG, "2.0"))

    def test_post_fits_telegram(self):
        html, truncated = releases.build_post(
            tag="v1.9", repo="igor-kim0/dash_t7", changelog=CHANGELOG)
        self.assertFalse(truncated)
        self.assertIn("DashHeadUnit 1.9", html)
        self.assertIn(releases.release_url("igor-kim0/dash_t7", "v1.9"), html)
        self.assertLessEqual(markdown.visible_len(html), markdown.SAFE_LIMIT)


class QueueTest(unittest.TestCase):
    def test_front_matter(self):
        meta, body = queue.parse_front_matter(
            "---\npublish_at: 2026-11-10T09:00:00+03:00\nphoto: a.png\n---\nПривет")
        self.assertEqual(meta["publish_at"], "2026-11-10T09:00:00+03:00")
        self.assertEqual(meta["photo"], "a.png")
        self.assertEqual(body, "Привет")

    def test_date_z(self):
        parsed = queue.parse_date("2026-11-10T09:00:00Z")
        self.assertEqual(parsed, dt.datetime(2026, 11, 10, 9, tzinfo=dt.timezone.utc))

    def test_due_posts_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            q = root / "queue"
            q.mkdir()
            (q / "later.md").write_text(
                "---\npublish_at: 2027-01-01T00:00:00Z\n---\nПозже", encoding="utf-8")
            sent, waiting = queue.run_queue(
                tg=None, chat_id="@x", queue_dir=q, sent_dir=root / "sent",
                now=dt.datetime(2026, 11, 1, tzinfo=dt.timezone.utc), dry_run=True)
            self.assertEqual((sent, waiting), (0, 1))

    def test_next_due(self):
        now = dt.datetime(2026, 11, 8, 12, tzinfo=dt.timezone.utc)
        with tempfile.TemporaryDirectory() as tmp:
            q = Path(tmp) / "queue"
            self.assertIsNone(queue.next_due(q, now))
            q.mkdir()
            self.assertIsNone(queue.next_due(q, now))
            (q / "broken.md").write_text(
                "---\npublish_at: завтра\n---\nТекст", encoding="utf-8")
            (q / "a.md").write_text(
                "---\npublish_at: 2026-11-09T09:00:00+03:00\n---\nА", encoding="utf-8")
            (q / "b.md").write_text(
                "---\npublish_at: 2026-11-08T18:00:00+03:00\n---\nБ", encoding="utf-8")
            self.assertEqual(queue.next_due(q, now),
                             dt.datetime(2026, 11, 8, 15, tzinfo=dt.timezone.utc))
            (q / "late.md").write_text(
                "---\npublish_at: 2026-11-08T11:00:00+03:00\n---\nВ", encoding="utf-8")
            self.assertEqual(queue.next_due(q, now), now)
            self.assertEqual(queue.next_due(q, now, future_only=True),
                             dt.datetime(2026, 11, 8, 15, tzinfo=dt.timezone.utc))


class ModerationTest(unittest.TestCase):
    def setUp(self):
        self.config = moderation.load_config(
            Path(__file__).parent / "moderation.json")

    def test_spam_words(self):
        msg = {"text": "Срочно! Быстрый доход и криптовалюта"}
        self.assertTrue(moderation._is_spam(msg, self.config))

    def test_links_limit(self):
        msg = {"text": "https://a.ru https://b.ru https://c.ru https://d.ru"}
        self.assertTrue(moderation._is_spam(msg, self.config))
        ok = {"text": "https://a.ru https://b.ru https://c.ru"}
        self.assertIsNone(moderation._is_spam(ok, self.config))

    def test_normal_message_passes(self):
        msg = {"text": "Поставил 1.8 на T7, климат работает. Спасибо!"}
        self.assertIsNone(moderation._is_spam(msg, self.config))


if __name__ == "__main__":
    unittest.main(verbosity=2)
