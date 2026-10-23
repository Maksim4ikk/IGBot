# -*- coding: utf-8 -*-
"""
igbot_engine.py — Стабільне ядро Instagram-бота для Android (веб-стек).

Ключові зміни:
  * єдиний веб-стек (www.instagram.com + веб User-Agent), узгоджений з sessionid з браузера;
  * реальна перевірка сесії (порожня відповідь більше не вважається входом);
  * ApiError замість хибного UserNotFound; повні відповіді IG пишуться в лог;
  * часовий пояс, User-Agent та Accept-Language беруться з налаштувань.
"""
from __future__ import annotations

import json
import logging
import random
import re
import sqlite3
import string
import threading
import time
from collections import Counter, deque
from dataclasses import dataclass, field, fields
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable, Optional
from urllib.parse import quote, unquote

import requests

STOP_WORKER = threading.Event()
STOP_SCRAPER = threading.Event()

WORKER_RUNNING = False
SCRAPER_RUNNING = False
SCRAPER_TASK = ""

LOG_BUFFER: deque[str] = deque(maxlen=250)
APP_DIR: Path = Path(".")

DEFAULT_STOP_WORDS = [
    "магазин*", "доставк*", "замовлен*", "опт", "роздріб", "наявност*",
    "прайс*", "ціна", "ціни", "директ", "direct", "shop", "store",
    "відправк*", "нова пошта", "накладн*", "купит*", "каталог*", "знижк*", "sale",
]


def get_local_tz(name: str = ""):
    """Часовий пояс із налаштувань. Якщо tzdata недоступна (буває на Android) —
    беремо часовий пояс самого телефону."""
    if name:
        try:
            from zoneinfo import ZoneInfo
            return ZoneInfo(name)
        except Exception:
            pass
    try:
        return datetime.now().astimezone().tzinfo or timezone.utc
    except Exception:
        return timezone.utc


class MemoryLogHandler(logging.Handler):
    def emit(self, record):
        try:
            LOG_BUFFER.append(self.format(record))
        except Exception:
            pass


log = logging.getLogger("igbot")


def init_android_env(files_dir: str) -> None:
    global APP_DIR
    APP_DIR = Path(files_dir)
    APP_DIR.mkdir(parents=True, exist_ok=True)

    root = logging.getLogger()
    root.setLevel(logging.INFO)
    if not any(isinstance(h, MemoryLogHandler) for h in root.handlers):
        mh = MemoryLogHandler()
        mh.setFormatter(logging.Formatter("%(asctime)s | %(message)s", datefmt="%H:%M:%S"))
        root.addHandler(mh)
        logging.getLogger("urllib3").setLevel(logging.WARNING)

    # Одноразове очищення статусів 'gone', помилково записаних старою версією.
    # Тепер 'gone' ставиться лише при справжньому 404, тому повторно не чистимо.
    try:
        db = Database()
        if db.get_meta("gone_cleaned_v2") is None:
            cleaned = db.conn.execute("DELETE FROM targets WHERE status='gone'").rowcount
            db.set_meta("gone_cleaned_v2", "1")
            if cleaned:
                log.info("Відновлено %d анкет для повторної перевірки", cleaned)
    except Exception:
        pass

    log.info("Ядро запущено. Готовий до роботи!")


# ════════════════════════════════════════════════════════════════════
# Класи помилок та моделей даних
# ════════════════════════════════════════════════════════════════════
class LoginRequired(Exception): pass
class FeedbackRequired(Exception): pass
class ChallengeRequired(Exception): pass
class PleaseWaitFewMinutes(Exception): pass
class UserNotFound(Exception): pass
class MediaNotFound(Exception): pass
class ApiError(Exception): pass          # неочікувана/порожня відповідь IG (не "користувача нема")
class StopWork(Exception): pass


@dataclass
class UserShort:
    pk: int
    username: str
    full_name: str = ""
    is_private: Optional[bool] = None


@dataclass
class UserInfo:
    pk: int
    username: str
    full_name: str
    biography: str
    is_private: bool
    is_business: bool
    category: str
    media_count: int
    follower_count: int
    following_count: int


@dataclass
class MediaItem:
    id: str
    pk: int
    code: str
    taken_at: datetime
    user: Optional[UserShort] = None


@dataclass
class StoryItem:
    pk: int
    user_pk: int
    taken_at: int


# ════════════════════════════════════════════════════════════════════
# Веб-клієнт Instagram (узгоджений з sessionid із браузера)
# ════════════════════════════════════════════════════════════════════
class WebInstaClient:
    WEB_UA = (
        "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
    )
    APP_ID = "936619743392459"
    BASE = "https://www.instagram.com"
    API = "https://www.instagram.com/api/v1"
    DEFAULT_LANG = "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"

    def __init__(self, user_agent: str = "", accept_language: str = ""):
        self.session = requests.Session()
        self.delay_range = [2.5, 5.0]
        self.session.headers.update({
            "User-Agent": (user_agent or "").strip() or self.WEB_UA,
            "X-IG-App-ID": self.APP_ID,
            "X-ASBD-ID": "129477",
            "X-IG-WWW-Claim": "0",
            "X-Requested-With": "XMLHttpRequest",
            "Accept": "*/*",
            "Accept-Language": (accept_language or "").strip() or self.DEFAULT_LANG,
            "Origin": self.BASE,
            "Referer": self.BASE + "/",
        })

    # ── службові ────────────────────────────────────────────────────
    def set_proxy(self, proxy_url: str) -> None:
        if proxy_url:
            self.session.proxies = {"http": proxy_url, "https": proxy_url}

    def _cookie(self, name: str) -> str:
        """Безпечне читання куки (без CookieConflictError при дублях по доменах)."""
        val = ""
        for c in self.session.cookies:
            if c.name == name:
                val = c.value
        return val

    def load_settings(self, path: Path) -> None:
        data = json.loads(path.read_text(encoding="utf-8"))
        for k, v in data.get("cookies", {}).items():
            self.session.cookies.set(k, v, domain=".instagram.com")

    def dump_settings(self, path: Path) -> None:
        cookies = {c.name: c.value for c in self.session.cookies}
        path.write_text(json.dumps({"cookies": cookies}, indent=2), encoding="utf-8")

    def _bootstrap_csrf(self) -> None:
        """Оновити csrftoken з головної сторінки Instagram."""
        # Старий токен може залишатися у збереженій cookie-сесії та не збігатися
        # з поточним sessionid. Видаляємо всі копії, щоб сервер видав актуальний.
        for cookie in list(self.session.cookies):
            if cookie.name == "csrftoken":
                try:
                    self.session.cookies.clear(
                        domain=cookie.domain,
                        path=cookie.path,
                        name=cookie.name,
                    )
                except KeyError:
                    pass

        try:
            self.session.get(
                self.BASE + "/",
                headers={"Accept": "text/html,application/xhtml+xml,*/*;q=0.8"},
                timeout=20,
                )
        except requests.RequestException as e:
            raise LoginRequired(f"Не вдалося оновити csrftoken: {e}") from e

        if not self._cookie("csrftoken"):
            raise LoginRequired(
                "Instagram не видав csrftoken. Перевірте sessionid, мережу або проксі."
            )

    def _req(self, method: str, url: str, delay: Optional[tuple] = None, **kwargs) -> dict:
        lo, hi = delay if delay else self.delay_range
        time.sleep(random.uniform(lo, hi))

        headers = kwargs.pop("headers", {})
        csrf = self._cookie("csrftoken")
        if csrf:
            headers["X-CSRFToken"] = csrf

        resp = self.session.request(method, url, headers=headers, timeout=25, **kwargs)

        if resp.status_code == 404:
            raise UserNotFound(f"404 Not Found ({url.split('?')[0][-40:]})")
        if resp.status_code == 429:
            raise PleaseWaitFewMinutes("Забагато запитів (429)")
        if "/accounts/login" in resp.url:
            raise LoginRequired("Сесія вимагає повторного входу")

        try:
            data = resp.json()
        except Exception:
            log.warning("IG не-JSON: %s %s | %s", resp.status_code, resp.url[-60:], resp.text[:200].replace("\n", " "))
            if resp.status_code in (401, 403):
                raise LoginRequired(f"Відмова доступу ({resp.status_code})")
            if resp.status_code >= 400:
                raise PleaseWaitFewMinutes(f"HTTP помилка {resp.status_code}")
            raise ApiError("Не-JSON відповідь (HTTP 200)")

        if not isinstance(data, dict):
            raise ApiError(f"Несподіваний формат відповіді: {str(data)[:150]}")

        msg = str(data.get("message", "")).lower()
        if data.get("require_login") is True or "login_required" in msg or "user_has_logged_out" in msg:
            raise LoginRequired(msg or "require_login")
        if "csrf token" in msg and ("missing" in msg or "incorrect" in msg):
            raise LoginRequired("Instagram відхилив csrftoken; оновлюю сесію")
        if "useragent mismatch" in msg:
            raise LoginRequired("useragent mismatch: вкажіть у web_user_agent той самий UA, що в браузері з sessionid")
        if "challenge_required" in msg or "checkpoint_required" in msg:
            raise ChallengeRequired("Підтвердіть вхід в офіційному додатку Instagram!")
        if "feedback_required" in msg or data.get("spam") is True:
            raise FeedbackRequired(data.get("feedback_message", "Action Block"))
        if "please wait" in msg or ("wait" in msg and resp.status_code == 400):
            raise PleaseWaitFewMinutes(msg)

        if data.get("status") == "fail":
            log.warning("IG fail (%s): %s", resp.status_code, str(data)[:300])
            raise ApiError(msg or f"status=fail ({resp.status_code})")
        return data

    # ── вхід ────────────────────────────────────────────────────────
    def login_by_sessionid(self, sessionid: str) -> None:
        clean_sid = unquote(sessionid.strip())
        self.session.cookies.set("sessionid", clean_sid, domain=".instagram.com")
        if ":" in clean_sid:
            self.session.cookies.set("ds_user_id", clean_sid.split(":")[0], domain=".instagram.com")
        self._bootstrap_csrf()
        self.verify_session()

    def login(self, username: str, password: str) -> None:
        self._bootstrap_csrf()
        headers = {"Referer": self.BASE + "/accounts/login/", "X-CSRFToken": self._cookie("csrftoken")}
        enc_password = f"#PWD_INSTAGRAM_BROWSER:0:{int(time.time())}:{password}"
        resp = self.session.post(
            self.BASE + "/api/v1/web/accounts/login/ajax/",
            data={
                "username": username,
                "enc_password": enc_password,
                "queryParams": "{}",
                "optIntoOneTap": "false",
            },
            headers=headers,
            timeout=25,
            )
        try:
            data = resp.json()
        except Exception:
            raise StopWork(f"Помилка входу (HTTP {resp.status_code}). Вставте куку sessionid у вкладці Акаунти.")

        if not data.get("authenticated"):
            if data.get("two_factor_required"):
                raise StopWork("Увімкнено 2FA: вставте куку sessionid у вкладці Акаунти")
            if "checkpoint" in str(data).lower() or "challenge" in str(data).lower():
                raise ChallengeRequired("Підтвердіть вхід у додатку Instagram ('Це був я')")
            raise StopWork(f"Вхід відхилено ({data.get('message', 'перевірте пароль або вставте sessionid')})")

    def verify_session(self) -> dict:
        """Справжня перевірка сесії: у відповіді обов'язково має бути користувач."""
        uid = self._cookie("ds_user_id")
        if not uid:
            raise LoginRequired("Немає ds_user_id — сесія невалідна")
        data = self._req("GET", f"{self.API}/users/{uid}/info/")
        if not data.get("user"):
            raise LoginRequired(f"Сесія невалідна: {str(data)[:150]}")
        return data

    # ── пошук / дані ────────────────────────────────────────────────
    def user_id_from_username(self, username: str) -> int:
        uname = username.strip().lstrip("@").lower()

        # 1) вебпошук (м'який до лімітів)
        try:
            data = self._req(
                "GET", f"{self.BASE}/web/search/topsearch/?context=user&include_reel=false&query={quote(uname)}")
            for row in data.get("users") or []:
                u = row.get("user") or row
                if str(u.get("username", "")).lower() == uname and u.get("pk"):
                    return int(u["pk"])
        except (ApiError, UserNotFound):
            pass

        # 2) точний профіль
        data = self._req("GET", f"{self.API}/users/web_profile_info/?username={quote(uname)}")
        u = ((data.get("data") or {}).get("user")) or {}
        if u.get("id"):
            return int(u["id"])
        raise UserNotFound(f"Користувача @{uname} не знайдено")

    def user_info(self, uid: int) -> UserInfo:
        data = self._req("GET", f"{self.API}/users/{uid}/info/")
        u = data.get("user")
        if not u:
            raise ApiError(f"user_info без user для ID {uid}: {str(data)[:200]}")
        return UserInfo(
            pk=int(u["pk"]),
            username=u.get("username", ""),
            full_name=u.get("full_name", "") or "",
            biography=u.get("biography", "") or "",
            is_private=bool(u.get("is_private", False)),
            is_business=bool(u.get("is_business", False)),
            category=u.get("category", "") or "",
            media_count=int(u.get("media_count", 0) or 0),
            follower_count=int(u.get("follower_count", 0) or 0),
            following_count=int(u.get("following_count", 0) or 0),
        )

    def user_medias(self, uid: int, amount: int = 4) -> list[MediaItem]:
        data = self._req("GET", f"{self.API}/feed/user/{uid}/?count={amount}")
        items: list[MediaItem] = []
        for m in (data.get("items") or [])[:amount]:
            ts = int(m.get("taken_at", time.time()))
            items.append(MediaItem(
                id=str(m.get("id", m.get("pk"))),
                pk=int(m.get("pk", 0)),
                code=str(m.get("code", "")),
                taken_at=datetime.fromtimestamp(ts, tz=timezone.utc),
            ))
        return items

    def user_followers_v1_chunk(self, uid: int, max_amount: int = 100) -> tuple[list[UserShort], str]:
        results: list[UserShort] = []
        max_id = ""
        while len(results) < max_amount:
            count = min(50, max_amount - len(results))
            url = f"{self.API}/friendships/{uid}/followers/?count={count}"
            if max_id:
                url += f"&max_id={max_id}"
            data = self._req("GET", url)
            users = data.get("users") or []
            if not users:
                break
            for u in users:
                results.append(UserShort(
                    pk=int(u["pk"]),
                    username=u.get("username", ""),
                    full_name=u.get("full_name", "") or "",
                    is_private=u.get("is_private"),
                ))
            max_id = data.get("next_max_id") or ""
            if not max_id:
                break
        return results[:max_amount], max_id

    @staticmethod
    def media_pk_from_url(url: str) -> int:
        m = re.search(r"/(?:p|reel|tv)/([A-Za-z0-9_-]+)", url)
        if not m:
            raise ValueError("Невірне посилання на пост")
        code = m.group(1)
        alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        pk = 0
        for ch in code:
            pk = pk * 64 + alphabet.index(ch)
        return pk

    def media_likers(self, media_pk: int) -> list[UserShort]:
        data = self._req("GET", f"{self.API}/media/{media_pk}/likers/")
        return [
            UserShort(
                pk=int(u["pk"]),
                username=u.get("username", ""),
                full_name=u.get("full_name", "") or "",
                is_private=u.get("is_private"),
            )
            for u in data.get("users") or []
        ]

    def hashtag_medias_recent(self, tag: str, amount: int = 100) -> list[MediaItem]:
        data = self._req("GET", f"{self.API}/tags/web_info/?tag_name={quote(tag)}")
        d = data.get("data") or data
        sections = list((d.get("recent") or {}).get("sections") or [])
        sections += list((d.get("top") or {}).get("sections") or [])
        if not sections:
            sections = data.get("sections") or []
        if not sections:
            log.warning("Хештег #%s: порожні секції. Відповідь: %s", tag, str(data)[:200])

        items: list[MediaItem] = []
        for sec in sections:
            for row in (sec.get("layout_content") or {}).get("medias", []):
                m = row.get("media")
                if m and m.get("user"):
                    u = m["user"]
                    ts = int(m.get("taken_at", time.time()))
                    items.append(MediaItem(
                        id=str(m.get("id", m.get("pk"))),
                        pk=int(m.get("pk", 0)),
                        code=str(m.get("code", "")),
                        taken_at=datetime.fromtimestamp(ts, tz=timezone.utc),
                        user=UserShort(
                            pk=int(u["pk"]),
                            username=u.get("username", ""),
                            full_name=u.get("full_name", "") or "",
                            is_private=u.get("is_private"),
                        ),
                    ))
                    if len(items) >= amount:
                        return items
        return items

    # ── сторіс / лайки ──────────────────────────────────────────────
    def user_stories(self, uid: int) -> list[StoryItem]:
        data = self._req("GET", f"{self.API}/feed/reels_media/?reel_ids={uid}")
        raw: list = []
        reels = data.get("reels")
        if isinstance(reels, dict):
            raw = (reels.get(str(uid)) or {}).get("items") or []
        if not raw:
            for r in data.get("reels_media") or []:
                owner = str((r.get("user") or {}).get("pk", r.get("id", "")))
                if owner == str(uid):
                    raw = r.get("items") or []
                    break
        stories: list[StoryItem] = []
        for s in raw:
            stories.append(StoryItem(
                pk=int(s["pk"]),
                user_pk=int(uid),
                taken_at=int(s.get("taken_at", time.time())),
            ))
        return stories

    def story_seen(self, stories: list[StoryItem]) -> bool:
        if not stories:
            return False
        now_ts = int(time.time())
        for st in stories:
            self._req(
                "POST", f"{self.API}/stories/reel/seen",
                delay=(0.6, 1.6),
                data={
                    "reelMediaId": str(st.pk),
                    "reelMediaOwnerId": str(st.user_pk),
                    "reelId": str(st.user_pk),
                    "reelMediaTakenAt": str(st.taken_at),
                    "viewSeenAt": str(now_ts),
                },
            )
        return True

    def media_like(self, media_id: str) -> bool:
        media_pk = str(media_id).split("_")[0]
        self._req("POST", f"{self.API}/web/likes/{media_pk}/like/")
        return True


# Зворотна сумісність зі старою назвою
MobileInstaClient = WebInstaClient


# ════════════════════════════════════════════════════════════════════
# Налаштування, База даних та Ліміти
# ════════════════════════════════════════════════════════════════════
@dataclass
class Settings:
    main_username: str = ""
    main_password: str = ""
    main_sessionid: str = ""
    main_proxy: str = ""

    scraper_username: str = ""
    scraper_password: str = ""
    scraper_sessionid: str = ""
    scraper_proxy: str = ""

    # Має збігатися з браузером, звідки взято sessionid (navigator.userAgent).
    # Порожньо = стандартний Android Chrome UA.
    web_user_agent: str = ""
    accept_language: str = "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"

    timezone: str = "Europe/Vienna"
    request_delay_min: float = 2.5
    request_delay_max: float = 5.0

    likes_per_hour: int = 18
    stories_per_hour: int = 50
    likes_per_day: int = 120
    stories_per_day: int = 500
    pace_jitter: float = 0.3
    active_start_hour: int = 9
    active_end_hour: int = 23
    warmup_days: int = 7
    warmup_start_factor: float = 0.3

    watch_stories: bool = True
    like_last_post: bool = True
    like_probability: float = 1.0
    max_stories_per_user: int = 5
    target_delay_min: float = 4.0
    target_delay_max: float = 12.0
    break_every_min: int = 12
    break_every_max: int = 25
    break_minutes_min: float = 4.0
    break_minutes_max: float = 12.0

    max_throttle_streak: int = 3
    throttle_base_minutes: float = 10.0
    block_cooldown_hours: float = 24.0
    max_attempts_per_target: int = 3
    max_fail_streak: int = 8
    stale_processing_minutes: int = 60
    idle_poll_seconds: int = 60

    max_following: int = 1500
    max_followers: int = 25000
    min_followers: int = 0
    min_posts: int = 1
    skip_business: bool = True
    scrape_delay_min: float = 3.0
    scrape_delay_max: float = 6.0
    scrape_break_every_min: int = 35
    scrape_break_every_max: int = 50
    scrape_break_minutes_min: float = 2.0
    scrape_break_minutes_max: float = 4.0
    stop_words: list = field(default_factory=lambda: list(DEFAULT_STOP_WORDS))

    @classmethod
    def load(cls) -> "Settings":
        s = cls()
        p = APP_DIR / "config.json"
        if p.exists():
            try:
                data = json.loads(p.read_text(encoding="utf-8"))
                valid = {f.name for f in fields(cls)}
                for k, v in data.items():
                    if k in valid:
                        setattr(s, k, v)
            except Exception as e:
                log.error("Помилка config.json: %s", e)
        return s

    def save(self) -> None:
        p = APP_DIR / "config.json"
        p.write_text(
            json.dumps({f.name: getattr(self, f.name) for f in fields(Settings)},
                       ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


def nap(seconds: float, stop_event: threading.Event) -> bool:
    end = time.monotonic() + max(0.0, seconds)
    while not stop_event.is_set():
        left = end - time.monotonic()
        if left <= 0:
            return False
        stop_event.wait(min(1.0, left))
    return True


def human_pause(lo: float, hi: float, stop_event: threading.Event) -> bool:
    return nap(random.uniform(lo, hi), stop_event)


def pick_latest(medias: list[MediaItem]) -> Optional[MediaItem]:
    return max(medias, key=lambda m: m.taken_at) if medias else None


def build_stop_regex(words: list[str]) -> Optional[re.Pattern]:
    parts = []
    for w in sorted({w.strip().lower() for w in words if w.strip()}, key=len, reverse=True):
        parts.append(re.escape(w[:-1]) + r"\w*" if w.endswith("*") else re.escape(w))
    if not parts:
        return None
    return re.compile(r"(?<!\w)(?:" + "|".join(parts) + r")(?!\w)", re.IGNORECASE)


def fmt_secs(s: float) -> str:
    s = int(s)
    return f"{s // 3600}г {s % 3600 // 60}хв" if s >= 3600 else f"{s // 60}хв {s % 60}с"


class Database:
    def __init__(self):
        db_path = str(APP_DIR / "igbot.db")
        self.conn = sqlite3.connect(db_path, timeout=30.0, isolation_level=None, check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA synchronous=NORMAL")
        self.conn.executescript("""
            CREATE TABLE IF NOT EXISTS targets (
                user_id INTEGER PRIMARY KEY,
                username TEXT,
                status TEXT NOT NULL,
                latest_media_id TEXT,
                latest_media_code TEXT,
                source TEXT,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_targets_status ON targets(status, updated_at);
            CREATE TABLE IF NOT EXISTS action_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account TEXT NOT NULL,
                kind TEXT NOT NULL,
                ts REAL NOT NULL,
                count INTEGER NOT NULL DEFAULT 1
            );
            CREATE INDEX IF NOT EXISTS idx_action ON action_log(account, kind, ts);
            CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
        """)
        self.conn.execute("DELETE FROM action_log WHERE ts < ?", (time.time() - 7 * 86400,))

    def exists(self, uid: int) -> bool:
        return self.conn.execute("SELECT 1 FROM targets WHERE user_id=?", (uid,)).fetchone() is not None

    def add_target(self, uid: int, username: str, status: str, source: str = "",
                   media_id: Optional[str] = None, media_code: Optional[str] = None) -> bool:
        now = time.time()
        cur = self.conn.execute(
            """INSERT OR IGNORE INTO targets
               (user_id, username, status, latest_media_id, latest_media_code, source, created_at, updated_at)
               VALUES (?,?,?,?,?,?,?,?)""",
            (uid, username, status, media_id, media_code, source, now, now))
        return cur.rowcount == 1

    def claim_next(self) -> Optional[dict]:
        c = self.conn
        c.execute("BEGIN IMMEDIATE")
        try:
            row = c.execute(
                """SELECT user_id, username, latest_media_id, latest_media_code
                   FROM targets WHERE status='ready' ORDER BY updated_at ASC LIMIT 1""").fetchone()
            if row:
                c.execute("UPDATE targets SET status='processing', updated_at=? WHERE user_id=?",
                          (time.time(), row[0]))
            c.execute("COMMIT")
        except Exception:
            c.execute("ROLLBACK")
            raise
        if not row:
            return None
        return {"user_id": row[0], "username": row[1], "media_id": row[2], "media_code": row[3]}

    def complete(self, uid: int, status: str, error: Optional[str] = None) -> None:
        self.conn.execute("UPDATE targets SET status=?, last_error=?, updated_at=? WHERE user_id=?",
                          (status, error, time.time(), uid))

    def fail(self, uid: int, error: str, max_attempts: int) -> None:
        self.conn.execute(
            """UPDATE targets SET attempts=attempts+1, last_error=?, updated_at=?,
               status=CASE WHEN attempts+1>=? THEN 'error' ELSE 'ready' END WHERE user_id=?""",
            (error[:300], time.time(), max_attempts, uid))

    def release(self, uid: int) -> None:
        self.conn.execute("UPDATE targets SET status='ready' WHERE user_id=? AND status='processing'", (uid,))

    def release_stale(self, older_than_s: float) -> int:
        cur = self.conn.execute(
            "UPDATE targets SET status='ready' WHERE status='processing' AND updated_at < ?",
            (time.time() - older_than_s,))
        return cur.rowcount

    def retry_errors(self) -> int:
        cur = self.conn.execute("UPDATE targets SET status='ready', attempts=0 WHERE status='error'")
        return cur.rowcount

    def stats(self) -> dict:
        return dict(self.conn.execute("SELECT status, COUNT(*) FROM targets GROUP BY status").fetchall())

    def log_action(self, account: str, kind: str, count: int = 1) -> None:
        self.conn.execute("INSERT INTO action_log(account, kind, ts, count) VALUES (?,?,?,?)",
                          (account, kind, time.time(), count))

    def used_since(self, account: str, kind: str, since: float) -> int:
        return self.conn.execute(
            "SELECT COALESCE(SUM(count),0) FROM action_log WHERE account=? AND kind=? AND ts>=?",
            (account, kind, since)).fetchone()[0]

    def oldest_since(self, account: str, kind: str, since: float) -> Optional[float]:
        return self.conn.execute(
            "SELECT MIN(ts) FROM action_log WHERE account=? AND kind=? AND ts>=?",
            (account, kind, since)).fetchone()[0]

    def last_action(self, account: str, kind: str) -> Optional[tuple]:
        return self.conn.execute(
            "SELECT ts, count FROM action_log WHERE account=? AND kind=? ORDER BY ts DESC LIMIT 1",
            (account, kind)).fetchone()

    def get_meta(self, key: str) -> Optional[str]:
        row = self.conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row[0] if row else None

    def set_meta(self, key: str, value: str) -> None:
        self.conn.execute("INSERT OR REPLACE INTO meta(key, value) VALUES (?,?)", (key, value))

    def clear_blocks(self) -> int:
        return self.conn.execute("DELETE FROM meta WHERE key LIKE 'blocked_until:%'").rowcount


class RateLimiter:
    def __init__(self, db: Database, account: str, cfg: Settings, stop_event: threading.Event):
        self.db, self.account, self.cfg = db, account, cfg
        self.stop_event = stop_event
        self.tz = get_local_tz(cfg.timezone)

    def now(self) -> datetime:
        return datetime.now(self.tz)

    def in_window(self) -> bool:
        return self.cfg.active_start_hour <= self.now().hour < self.cfg.active_end_hour

    def seconds_to_window(self, force: bool = False) -> float:
        if not force and self.in_window():
            return 0.0
        now = self.now()
        start = now.replace(hour=self.cfg.active_start_hour, minute=0, second=0, microsecond=0)
        if start <= now:
            start += timedelta(days=1)
        return (start - now).total_seconds() + random.uniform(0, 900)

    def warmup_factor(self) -> float:
        key = f"first_run:{self.account}"
        raw = self.db.get_meta(key)
        if raw is None:
            self.db.set_meta(key, str(time.time()))
            raw = str(time.time())
        c = self.cfg
        if c.warmup_days <= 0:
            return 1.0
        days = (time.time() - float(raw)) / 86400
        if days >= c.warmup_days:
            return 1.0
        return c.warmup_start_factor + (1 - c.warmup_start_factor) * days / c.warmup_days

    def _base(self, kind: str, period: str) -> int:
        return getattr(self.cfg, f"{'likes' if kind == 'like' else 'stories'}_per_{period}")

    def per_hour(self, kind: str) -> int:
        return max(1, int(self._base(kind, "hour") * self.warmup_factor()))

    def per_day(self, kind: str) -> int:
        return max(1, int(self._base(kind, "day") * self.warmup_factor()))

    def day_start_ts(self) -> float:
        return self.now().replace(hour=0, minute=0, second=0, microsecond=0).timestamp()

    def used_today(self, kind: str) -> int:
        return self.db.used_since(self.account, kind, self.day_start_ts())

    def exhausted_today(self, kind: str) -> bool:
        return self.used_today(kind) >= self.per_day(kind)

    def wait_for_slot(self, kind: str) -> bool:
        j = self.cfg.pace_jitter
        jitter = random.uniform(1 - j, 1 + j)
        while not self.stop_event.is_set():
            wait = self.seconds_to_window()
            if wait > 0:
                log.info("Нічний час. Сплю %s", fmt_secs(wait))
                nap(wait, self.stop_event)
                continue
            if self.exhausted_today(kind):
                return False
            cap = self.per_hour(kind)
            since = time.time() - 3600
            if self.db.used_since(self.account, kind, since) >= cap:
                oldest = self.db.oldest_since(self.account, kind, since) or time.time()
                nap(max(5.0, oldest + 3600 - time.time()) + random.uniform(1, 20), self.stop_event)
                continue
            last = self.db.last_action(self.account, kind)
            if last:
                gap = 3600 / cap * last[1] * jitter
                remaining = last[0] + gap - time.time()
                if remaining > 0:
                    nap(remaining, self.stop_event)
                    continue
            return True
        return False


# ════════════════════════════════════════════════════════════════════
# Базовий бот, Парсер та Основний виконавець
# ════════════════════════════════════════════════════════════════════
class BaseBot:
    delay_override: Optional[list] = None   # перевизначається в нащадках

    def __init__(self, cfg: Settings, db: Database, username: str, password: str,
                 stop_event: threading.Event, proxy: str = "", sessionid: str = ""):
        self.cfg, self.db = cfg, db
        self.username, self.password = username.strip(), password.strip()
        self.proxy = proxy.strip()
        self.sessionid = sessionid.strip()
        self.stop_event = stop_event
        if not self.username:
            raise StopWork("Вкажіть логін акаунта у вкладці 'Акаунти'!")
        self.session_path = APP_DIR / "sessions" / f"{self.username}.json"
        self.throttle_streak = 0
        self._check_blocked()
        self.cl = self._new_client()
        self._login()

    def _new_client(self) -> WebInstaClient:
        cl = WebInstaClient(self.cfg.web_user_agent, self.cfg.accept_language)
        cl.delay_range = list(self.delay_override or [self.cfg.request_delay_min, self.cfg.request_delay_max])
        if self.proxy:
            cl.set_proxy(self.proxy)
        return cl

    def _check_blocked(self) -> None:
        raw = self.db.get_meta(f"blocked_until:{self.username}")
        if raw and float(raw) > time.time():
            left = float(raw) - time.time()
            raise StopWork(f"@{self.username} на паузі після блоку ще {fmt_secs(left)}")

    def _set_block(self, reason: str) -> None:
        until = time.time() + self.cfg.block_cooldown_hours * 3600
        self.db.set_meta(f"blocked_until:{self.username}", str(until))
        log.error("@%s: %s. Пауза %.0f год.", self.username, reason, self.cfg.block_cooldown_hours)

    def _login(self) -> None:
        if self.session_path.exists():
            try:
                log.info("[%s] Перевірка збереженої сесії...", self.username)
                self.cl.load_settings(self.session_path)
                saved_sid = self.cl._cookie("sessionid")
                if self.sessionid and saved_sid and saved_sid != unquote(self.sessionid):
                    raise LoginRequired("у налаштуваннях новий sessionid")
                self.cl._bootstrap_csrf()
                self.cl.verify_session()
                log.info("[%s] Сесію успішно відновлено!", self.username)
                return
            except PleaseWaitFewMinutes:
                log.info("[%s] Сесія активна", self.username)
                return
            except Exception as e:
                log.warning("[%s] Збережена сесія не підійшла (%s), новий вхід...", self.username, e)
        self._fresh_login()

    def _fresh_login(self) -> None:
        self.cl = self._new_client()
        logged = False
        if self.sessionid:
            try:
                log.info("[%s] Вхід через sessionid...", self.username)
                self.cl.login_by_sessionid(self.sessionid)
                logged = True
            except Exception as e:
                log.warning("[%s] sessionid не підійшов (%s), пробуємо пароль...", self.username, e)
                self.cl = self._new_client()

        if not logged:
            if not self.password:
                raise StopWork(f"sessionid недійсний або не вказаний, а пароля для @{self.username} немає")
            log.info("[%s] Вхід за логіном та паролем...", self.username)
            self.cl.login(self.username, self.password)
            self.cl.verify_session()

        self.save_session()
        log.info("[%s] Авторизація успішна! Сесію збережено.", self.username)

    def save_session(self) -> None:
        try:
            self.session_path.parent.mkdir(parents=True, exist_ok=True)
            self.cl.dump_settings(self.session_path)
        except Exception as e:
            log.warning("Помилка збереження сесії: %s", e)

    def api(self, fn: Callable, *args, **kwargs):
        relogged, net_tries = False, 0
        while True:
            if self.stop_event.is_set():
                raise StopWork("Зупинено користувачем")
            try:
                res = fn(*args, **kwargs)
                self.throttle_streak = 0
                return res
            except LoginRequired as e:
                if relogged:
                    raise StopWork(f"Сесія відхилена ({e}). Оновіть sessionid у вкладці Акаунти.")
                relogged = True
                log.warning("[%s] Повторна авторизація (%s)...", self.username, e)
                self._fresh_login()
            except FeedbackRequired as e:
                self._set_block(f"Action Block ({e})")
                raise StopWork("Спіймано Action Block!")
            except ChallengeRequired as e:
                raise StopWork(str(e))
            except PleaseWaitFewMinutes as e:
                self.throttle_streak += 1
                if self.throttle_streak > self.cfg.max_throttle_streak:
                    self._set_block("Троттлінг запитів (429)")
                    raise StopWork("Instagram тимчасово обмежив IP (429). Увімкніть/вимкніть Режим польоту.")
                wait = min(self.cfg.throttle_base_minutes * 60 * 2 ** (self.throttle_streak - 1), 1800)
                log.warning("Ліміт запитів (%s). Пауза %s...", e, fmt_secs(wait))
                nap(wait, self.stop_event)
            except requests.RequestException as e:
                net_tries += 1
                if net_tries > 3:
                    raise StopWork(f"Помилка мережі: {e}")
                nap(15 * net_tries, self.stop_event)


class ScraperBot(BaseBot):
    delay_override = [2.0, 4.0]

    def __init__(self, cfg: Settings, db: Database):
        super().__init__(
            cfg, db,
            username=cfg.scraper_username,
            password=cfg.scraper_password,
            stop_event=STOP_SCRAPER,
            proxy=cfg.scraper_proxy,
            sessionid=cfg.scraper_sessionid,
        )
        self.stop_re = build_stop_regex(self.cfg.stop_words)

    def scrape(self, source_type: str, target: str, amount: int = 100) -> None:
        target = target.strip()
        if source_type == "followers":
            uname = target.lstrip("@")
            log.info("[Парсер] Шукаю ID донора @%s...", uname)
            uid = self.api(self.cl.user_id_from_username, uname)
            log.info("[Парсер] ID @%s = %d. Завантажую підписників (до %d)...", uname, uid, amount)
            followers, _ = self.api(self.cl.user_followers_v1_chunk, uid, max_amount=amount)
            self._process(followers, f"followers:{uname}")
        elif source_type == "likers":
            pk = self.cl.media_pk_from_url(target)
            log.info("[Парсер] Завантажую лайкерів поста...")
            likers = self.api(self.cl.media_likers, pk)
            self._process(likers, f"likers:{pk}")
        elif source_type == "hashtag":
            tag = target.lstrip("#")
            log.info("[Парсер] Завантажую пости за хештегом #%s...", tag)
            medias = self.api(self.cl.hashtag_medias_recent, tag, amount=amount)
            seen: dict[int, UserShort] = {}
            for m in medias:
                if m.user and m.user.pk not in seen:
                    seen[m.user.pk] = m.user
            self._process(list(seen.values()), f"hashtag:{tag}")

    def _evaluate(self, uid: int, uname: str):
        c = self.cfg
        info = self.api(self.cl.user_info, uid)
        name = info.username or uname
        if info.is_private:
            return name, "skipped_private", None
        if (info.media_count < max(1, c.min_posts) or info.following_count > c.max_following
                or info.follower_count > c.max_followers or info.follower_count < c.min_followers):
            return name, "skipped_numbers", None
        if c.skip_business and info.is_business:
            return name, "skipped_commercial", None
        if self.stop_re:
            text = " ".join(filter(None, [info.full_name, info.biography, info.category]))
            if self.stop_re.search(text):
                return name, "skipped_commercial", None

        latest = pick_latest(self.api(self.cl.user_medias, uid, amount=4))
        if latest is None:
            return name, "skipped_dead", None
        return name, "ready", latest

    def _process(self, cands: list[UserShort], source: str) -> None:
        new = [u for u in cands if not self.db.exists(u.pk)]
        log.info("[Парсер] Отримано %d анкет (нових: %d)", len(cands), len(new))
        counter = Counter()
        since_break = 0
        fail_streak = 0
        next_break = random.randint(self.cfg.scrape_break_every_min, self.cfg.scrape_break_every_max)

        try:
            for i, u in enumerate(new, 1):
                if self.stop_event.is_set():
                    break
                uid, uname = u.pk, u.username

                # 1. Миттєве відсіювання приватних (0 запитів до API)
                if u.is_private is True:
                    self.db.add_target(uid, uname, "skipped_private", source)
                    counter["skipped_private"] += 1
                    continue

                # 2. Миттєве відсіювання магазинів за іменем (0 запитів до API)
                if self.stop_re and self.stop_re.search(f"{uname} {u.full_name}"):
                    self.db.add_target(uid, uname, "skipped_commercial", source)
                    counter["skipped_commercial"] += 1
                    log.info("[Парсер] [%d/%d] @%s -> skipped_commercial (за назвою)", i, len(new), uname)
                    continue

                try:
                    name, status, media = self._evaluate(uid, uname)
                    fail_streak = 0
                except UserNotFound as e:
                    # справжній 404: акаунт видалено/недоступний
                    log.warning("[Парсер] [%d/%d] @%s недоступний (%s)", i, len(new), uname, e)
                    self.db.add_target(uid, uname, "skipped_dead", source)
                    counter["skipped_dead"] += 1
                    continue
                except StopWork:
                    raise
                except Exception as e:
                    fail_streak += 1
                    counter["error"] += 1
                    log.error("[Парсер] [%d/%d] @%s помилка: %s", i, len(new), uname, e)
                    if fail_streak >= 5:
                        raise StopWork("5 помилок поспіль — зупиняю парсер. Дивіться рядки 'IG fail'/'IG не-JSON' у логах.")
                    human_pause(6, 12, self.stop_event)
                    continue

                self.db.add_target(uid, name, status, source,
                                   str(media.id) if media else None, str(media.code) if media else None)
                counter[status] += 1
                log.info("[Парсер] [%d/%d] @%s -> %s", i, len(new), name, status)

                since_break += 1
                if since_break >= next_break and i < len(new):
                    mins = random.uniform(self.cfg.scrape_break_minutes_min, self.cfg.scrape_break_minutes_max)
                    log.info("[Парсер] Перерва %.1f хв...", mins)
                    nap(mins * 60, self.stop_event)
                    since_break = 0
                    next_break = random.randint(self.cfg.scrape_break_every_min, self.cfg.scrape_break_every_max)
                else:
                    human_pause(self.cfg.scrape_delay_min, self.cfg.scrape_delay_max, self.stop_event)
        finally:
            self.save_session()
            log.info(
                "[Парсер] Завершено! ready: %d | приватних: %d | комерції: %d | за цифрами: %d | помилок: %d",
                counter["ready"], counter["skipped_private"],
                counter["skipped_commercial"], counter["skipped_numbers"], counter["error"]
            )


class MainWorker(BaseBot):
    def __init__(self, cfg: Settings, db: Database):
        super().__init__(
            cfg, db,
            username=cfg.main_username,
            password=cfg.main_password,
            stop_event=STOP_WORKER,
            proxy=cfg.main_proxy,
            sessionid=cfg.main_sessionid,
        )
        self.limiter = RateLimiter(self.db, self.username, self.cfg, STOP_WORKER)
        self.processed = 0
        self.fail_streak = 0
        self.since_break = 0
        self.next_break = random.randint(self.cfg.break_every_min, self.cfg.break_every_max)

    def _available_kinds(self) -> list[str]:
        kinds = []
        if self.cfg.like_last_post and not self.limiter.exhausted_today("like"):
            kinds.append("like")
        if self.cfg.watch_stories and not self.limiter.exhausted_today("story"):
            kinds.append("story")
        return kinds

    def run_daemon(self) -> None:
        c = self.cfg
        self.db.release_stale(c.stale_processing_minutes * 60)
        log.info("[Воркер] Старт @%s (лайків %d/год, %d/день, пояс %s)",
                 self.username, self.limiter.per_hour("like"), self.limiter.per_day("like"), c.timezone)
        try:
            while not self.stop_event.is_set():
                wait = self.limiter.seconds_to_window()
                if wait > 0:
                    log.info("[Воркер] Нічний режим. Сплю %s", fmt_secs(wait))
                    if nap(wait, self.stop_event):
                        break
                    continue
                kinds = self._available_kinds()
                if not kinds:
                    wait = self.limiter.seconds_to_window(force=True)
                    log.info("[Воркер] Денний ліміт виконано! Сплю до ранку (%s)", fmt_secs(wait))
                    if nap(wait, self.stop_event):
                        break
                    continue
                target = self.db.claim_next()
                if not target:
                    log.info("[Воркер] Черга ready порожня. Чекаю %d с...", c.idle_poll_seconds)
                    if nap(c.idle_poll_seconds, self.stop_event):
                        break
                    continue
                self._handle(target, kinds)
                self._after_target()
        finally:
            self.save_session()
            log.info("[Воркер] Роботу зупинено.")

    def _handle(self, t: dict, kinds: list[str]) -> None:
        uid = t["user_id"]
        try:
            did = self._perform(t, kinds)
        except StopWork:
            self.db.release(uid)
            raise
        except (UserNotFound, MediaNotFound) as e:
            self.db.complete(uid, "gone", str(e)[:200])
            return
        except Exception as e:
            log.error("[Воркер] @%s: %s", t["username"], e)
            self.db.fail(uid, str(e), self.cfg.max_attempts_per_target)
            self.fail_streak += 1
            if self.fail_streak >= self.cfg.max_fail_streak:
                raise StopWork(f"{self.fail_streak} помилок поспіль — зупиняю воркер. Дивіться логи 'IG fail'.")
            return
        self.fail_streak = 0
        self.db.complete(uid, "interacted" if did else "skipped_nothing")

    def _perform(self, t: dict, kinds: list[str]) -> bool:
        c, uid, uname = self.cfg, t["user_id"], t["username"]
        plan = []
        if "story" in kinds:
            plan.append("story")
        if "like" in kinds and t["media_id"] and random.random() < c.like_probability:
            plan.append("like")
        random.shuffle(plan)

        did_any = False
        for i, action in enumerate(plan):
            if i > 0:
                human_pause(3, 8, self.stop_event)
            if action == "story":
                if not self.limiter.wait_for_slot("story"):
                    continue
                stories = self.api(self.cl.user_stories, uid)[: c.max_stories_per_user]
                if not stories:
                    continue
                self.api(self.cl.story_seen, stories)
                self.db.log_action(self.username, "story", len(stories))
                log.info("[Воркер] 👁 @%s: переглянуто %d сторіс", uname, len(stories))
                did_any = True
            else:
                if not self.limiter.wait_for_slot("like"):
                    continue
                if self.api(self.cl.media_like, t["media_id"]):
                    self.db.log_action(self.username, "like")
                    log.info("[Воркер] ❤️ @%s: лайк на %s (%d/%d)",
                             uname, t["media_code"], self.limiter.used_today("like"), self.limiter.per_day("like"))
                    did_any = True
        return did_any

    def _after_target(self) -> None:
        c = self.cfg
        self.processed += 1
        if self.processed % 10 == 0:
            self.save_session()
        self.since_break += 1
        if self.since_break >= self.next_break:
            mins = random.uniform(c.break_minutes_min, c.break_minutes_max)
            log.info("[Воркер] Мікро-перерва %.1f хв", mins)
            nap(mins * 60, self.stop_event)
            self.since_break = 0
            self.next_break = random.randint(c.break_every_min, c.break_every_max)
        else:
            human_pause(c.target_delay_min, c.target_delay_max, self.stop_event)


# ════════════════════════════════════════════════════════════════════
# Функції для виклику з кнопок Android-додатка
# ════════════════════════════════════════════════════════════════════
def start_worker_thread() -> None:
    global WORKER_RUNNING
    if WORKER_RUNNING:
        return
    STOP_WORKER.clear()
    WORKER_RUNNING = True

    def _target():
        global WORKER_RUNNING
        try:
            cfg = Settings.load()
            db = Database()
            MainWorker(cfg, db).run_daemon()
        except Exception as e:
            log.error("[Воркер Стоп] %s", e)
        finally:
            WORKER_RUNNING = False

    threading.Thread(target=_target, daemon=True).start()


def stop_worker_thread() -> None:
    log.info("Зупинка Воркера...")
    STOP_WORKER.set()


def start_scraper_thread(source_type: str, target: str, amount: int) -> None:
    global SCRAPER_RUNNING, SCRAPER_TASK
    if SCRAPER_RUNNING:
        return
    STOP_SCRAPER.clear()
    SCRAPER_RUNNING = True
    SCRAPER_TASK = f"{source_type}: {target}"

    def _target():
        global SCRAPER_RUNNING, SCRAPER_TASK
        try:
            log.info("[Парсер] Запуск завдання %s (%s)...", source_type, target)
            cfg = Settings.load()
            db = Database()
            ScraperBot(cfg, db).scrape(source_type, target, int(amount))
        except Exception as e:
            log.error("[Парсер Стоп] %s", e)
        finally:
            SCRAPER_RUNNING = False
            SCRAPER_TASK = ""

    threading.Thread(target=_target, daemon=True).start()


def stop_scraper_thread() -> None:
    log.info("Зупинка Парсера...")
    STOP_SCRAPER.set()


def get_dashboard_json() -> str:
    cfg = Settings.load()
    db = Database()
    stats = db.stats()
    now = time.time()
    acc = cfg.main_username.strip()

    likes_today, likes_day_max, likes_hr, likes_hr_max = 0, cfg.likes_per_day, 0, cfg.likes_per_hour
    stories_today, stories_day_max, stories_hr, stories_hr_max = 0, cfg.stories_per_day, 0, cfg.stories_per_hour
    warmup = 1.0

    if acc:
        try:
            lim = RateLimiter(db, acc, cfg, STOP_WORKER)
            warmup = round(lim.warmup_factor(), 2)
            likes_today = lim.used_today("like")
            likes_day_max = lim.per_day("like")
            likes_hr = db.used_since(acc, "like", now - 3600)
            likes_hr_max = lim.per_hour("like")

            stories_today = lim.used_today("story")
            stories_day_max = lim.per_day("story")
            stories_hr = db.used_since(acc, "story", now - 3600)
            stories_hr_max = lim.per_hour("story")
        except Exception as e:
            log.error("Помилка лімітера: %s", e)

    payload = {
        "worker_running": WORKER_RUNNING,
        "scraper_running": SCRAPER_RUNNING,
        "scraper_task": SCRAPER_TASK,
        "ready": stats.get("ready", 0),
        "interacted": stats.get("interacted", 0),
        "skipped": sum(v for k, v in stats.items() if k.startswith("skipped")),
        "errors": stats.get("error", 0) + stats.get("gone", 0),
        "warmup": warmup,
        "likes_today": likes_today,
        "likes_day_max": likes_day_max,
        "likes_hr": likes_hr,
        "likes_hr_max": likes_hr_max,
        "stories_today": stories_today,
        "stories_day_max": stories_day_max,
        "stories_hr": stories_hr,
        "stories_hr_max": stories_hr_max,
        "logs": list(LOG_BUFFER)[-60:],
    }
    return json.dumps(payload, ensure_ascii=False)


def get_settings_json() -> str:
    cfg = Settings.load()
    return json.dumps({f.name: getattr(cfg, f.name) for f in fields(Settings)}, ensure_ascii=False)


def save_settings_json(raw_json: str) -> None:
    cfg = Settings.load()
    data = json.loads(raw_json)
    valid_keys = {f.name for f in fields(Settings)}
    for k, v in data.items():
        if k in valid_keys:
            setattr(cfg, k, v)
    cfg.save()
    log.info("Налаштування збережено!")


def unlock_all() -> None:
    db = Database()
    b = db.clear_blocks()
    s = db.release_stale(0)
    r = db.retry_errors()
    g = db.conn.execute("DELETE FROM targets WHERE status='gone'").rowcount
    log.info("Розблоковано: %d, завислих: %d, помилок: %d, відновлено gone: %d", b, s, r, g)

    # ════════════════════════════════════════════════════════════════════
# Акаунти: збереження після входу через WebView / вихід
# ════════════════════════════════════════════════════════════════════
def register_account(kind: str, cookies_json: str, user_agent: str, username_hint: str = "") -> str:
    """Зберігає акаунт після входу через WebView.
    Нікнейм приходить із WebView (username_hint), тому до Instagram тут запитів
    не робимо — це прибирає помилку 429 одразу після входу."""
    if kind not in ("main", "scraper"):
        raise ValueError("kind має бути 'main' або 'scraper'")

    cookies = json.loads(cookies_json)
    sid = unquote(str(cookies.get("sessionid", ""))).strip()
    uid = str(cookies.get("ds_user_id", "")).strip() or sid.split(":")[0]
    if not sid or not uid.isdigit():
        raise LoginRequired("Instagram не видав sessionid — вхід не завершено")

    cfg = Settings.load()
    cl = WebInstaClient(user_agent, cfg.accept_language)
    cl.delay_range = [0.5, 1.5]

    proxy = (cfg.main_proxy if kind == "main" else cfg.scraper_proxy).strip()
    if proxy:
        cl.set_proxy(proxy)

    for k, v in cookies.items():
        if k not in ("sessionid", "ds_user_id"):
            cl.session.cookies.set(k, str(v), domain=".instagram.com")
    cl.session.cookies.set("sessionid", sid, domain=".instagram.com")
    cl.session.cookies.set("ds_user_id", uid, domain=".instagram.com")

    username = (username_hint or "").strip().lstrip("@")
    full_name = ""
    if not username:
        # WebView не віддав нікнейм — пробуємо запитом, але 429 більше не фатальний
        try:
            info = cl.user_info(int(uid))
            username, full_name = info.username, info.full_name
        except (PleaseWaitFewMinutes, ApiError, requests.RequestException) as e:
            log.warning("Не вдалося дізнатися нікнейм (%s). Тимчасово: id%s", e, uid)
            username = f"id{uid}"

    old_username = cfg.main_username if kind == "main" else cfg.scraper_username
    if kind == "main":
        cfg.main_username = username
        cfg.main_sessionid = sid
        cfg.main_password = ""
    else:
        cfg.scraper_username = username
        cfg.scraper_sessionid = sid
        cfg.scraper_password = ""
    if user_agent:
        cfg.web_user_agent = user_agent
    cfg.save()

    sessions = APP_DIR / "sessions"
    sessions.mkdir(parents=True, exist_ok=True)
    if old_username and old_username != username:
        try:
            (sessions / f"{old_username}.json").unlink()
        except FileNotFoundError:
            pass
    cl.dump_settings(sessions / f"{username}.json")

    log.info("Акаунт @%s (%s) збережено", username, "основний" if kind == "main" else "технічний")
    return json.dumps({"username": username, "full_name": full_name}, ensure_ascii=False)


def logout_account(kind: str) -> None:
    """Вихід з акаунта: зупиняє відповідний процес, чистить дані та файл сесії."""
    cfg = Settings.load()
    if kind == "main":
        uname = cfg.main_username
        STOP_WORKER.set()
        cfg.main_username = ""
        cfg.main_password = ""
        cfg.main_sessionid = ""
    elif kind == "scraper":
        uname = cfg.scraper_username
        STOP_SCRAPER.set()
        cfg.scraper_username = ""
        cfg.scraper_password = ""
        cfg.scraper_sessionid = ""
    else:
        return

    if uname:
        try:
            (APP_DIR / "sessions" / f"{uname}.json").unlink()
        except FileNotFoundError:
            pass
    cfg.save()
    log.info("Вихід з акаунта @%s виконано", uname or "?")