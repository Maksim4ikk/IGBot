"""Міст Python -> Kotlin (WebView).

Усі виклики Kotlin-класу com.maks.igbot.web.WebBridge робляться ТУТ і більше ніде:
лайк поста, пошук свіжого поста профілю, ID акаунта, пости хештегу, автор поста.
Сторінки відкриваються у справжньому WebView, тому Instagram бачить звичайного користувача.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Iterable

from .errors import StopWork

BRIDGE_CLASS = "com.maks.igbot.web.WebBridge"
LOGIN_HINT = "WebView не авторизований в Instagram — увійдіть в акаунт у вкладці «Акаунти»"


@dataclass
class WebResult:
    """Відповідь Kotlin-мосту. status: ok | private | no_posts | not_found | liked | already_liked |
    no_button | not_confirmed | unknown | timeout | error  (login перетворюється на StopWork)."""
    status: str
    data: dict = field(default_factory=dict)
    detail: str = ""

    @property
    def ok(self) -> bool:
        return self.status == "ok"

    def get(self, key: str, default: str = "") -> str:
        return str(self.data.get(key, default) or default)

    def list(self, key: str) -> list[str]:
        return [x.strip() for x in self.get(key).split(",") if x.strip()]


class WebBridge:
    """Обгортка над Kotlin-мостом для одного акаунта (його куки + User-Agent)."""

    def __init__(self, cookie_jar: Iterable, user_agent: str = "", task_id: str = "worker"):
        self._jar = cookie_jar
        self._ua = (user_agent or "").strip()
        self._task_id = task_id

    def _cookies_json(self) -> str:
        return json.dumps({c.name: c.value for c in self._jar if c.value})

    def _call(self, fn: str, *args) -> WebResult:
        try:
            from java import jclass  # доступно лише в Chaquopy (Android)
        except ImportError as e:
            raise StopWork("Ця функція працює лише в Android-застосунку") from e
        try:
            raw = getattr(jclass(BRIDGE_CLASS), fn)(*args, self._ua, self._cookies_json(), self._task_id)
        except UnicodeError as e:  # напр. обрізаний посередині емодзі в тексті сторінки
            return WebResult("error", {}, f"некоректний текст від WebView: {e}")
        data = json.loads(str(raw))
        status = str(data.get("status", "error"))
        if status in ("login", "login_required"):
            raise StopWork(LOGIN_HINT)
        if status == "cancelled":
            raise StopWork("Зупинено користувачем")
        return WebResult(status, data, str(data.get("detail", "")))

    # ── дії ─────────────────────────────────────────────────────────
    def like(self, code: str) -> WebResult:
        """Відкрити пост і натиснути «подобається». liked | already_liked | no_button | not_confirmed | ..."""
        return self._call("like", code)

    def latest_posts(self, username: str) -> WebResult:
        """Коди до 12 постів зі сторінки профілю (ok + codes | private | no_posts | not_found | unknown)."""
        return self._call("latestPost", username)

    def profile_id(self, username: str) -> WebResult:
        """Числовий ID акаунта зі сторінки профілю (ok + id | not_found | unknown)."""
        return self._call("profileId", username)

    def hashtag_posts(self, tag: str, amount: int) -> WebResult:
        """Коди постів зі сторінки хештегу (ok + codes | unknown)."""
        return self._call("hashtagPosts", tag, int(amount))

    def post_author(self, code: str) -> WebResult:
        """Автор поста (ok + username, ids | unknown)."""
        return self._call("postAuthor", code)

    def post_likers(self, code: str, amount: int) -> WebResult:
        """Повний список тих, хто лайкнув пост, з екрана «Вподобання» (ok + names | unknown).
        На відміну від API (не більше ~100) тут список прокручується до потрібної кількості."""
        return self._call("postLikers", code, int(amount))

    def post_commenters(self, code: str, amount: int) -> WebResult:
        """Автори коментарів зі сторінки коментарів поста (ok + names | unknown). Запасний шлях, коли API не віддав."""
        return self._call("postCommenters", code, int(amount))


def request_cancel(task_id: str) -> None:
    """Cancels the active WebView operation owned by this task."""
    if task_id not in ("worker", "scraper"):
        return
    try:
        from java import jclass
        jclass(BRIDGE_CLASS).cancel(task_id)
    except Exception:
        pass
