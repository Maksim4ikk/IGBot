from __future__ import annotations

import json
import math
from dataclasses import dataclass, field, fields

from . import state
from .state import log

DEFAULT_STOP_WORDS = [
    "магазин*", "доставк*", "замовлен*", "опт", "роздріб", "наявност*",
    "прайс*", "ціна", "ціни", "директ", "direct", "shop", "store",
    "відправк*", "нова пошта", "накладн*", "купит*", "каталог*", "знижк*", "sale",
]
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

    watch_stories: bool = False  # сторіс іде через API, який Instagram блокує — типово вимкнено
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
    scrape_amount: int = 50           # скільки анкет збирати за один запуск (за замовчуванням)
    scrape_delay_min: float = 8.0     # пауза між анкетами, с
    scrape_delay_max: float = 15.0
    migrated_delays: bool = False     # службове: старі швидкі паузи 3–6 с піднято до нових (один раз)
    scrape_break_every_min: int = 35
    scrape_break_every_max: int = 50
    scrape_break_minutes_min: float = 2.0
    scrape_break_minutes_max: float = 4.0
    stop_words: list = field(default_factory=lambda: list(DEFAULT_STOP_WORDS))

    @classmethod
    def load(cls) -> "Settings":
        with state.SETTINGS_LOCK:
            s = cls()
            p = state.APP_DIR / "config.json"
            migrated = False
            if p.exists():
                try:
                    data = json.loads(p.read_text(encoding="utf-8"))
                    valid = {f.name for f in fields(cls)}
                    for k, v in data.items():
                        if k in valid:
                            setattr(s, k, v)
                    if not data.get("migrated_delays"):
                        # Колишні типові паузи 3–6 с надто швидкі: Instagram починає не віддавати пости.
                        # Піднімаємо до нових типових лише раз; далі вибір користувача поважається.
                        migrated = True
                        if float(s.scrape_delay_min) <= 3.0 and float(s.scrape_delay_max) <= 6.0:
                            s.scrape_delay_min, s.scrape_delay_max = 8.0, 15.0
                            log.info("Паузу між анкетами парсера піднято з 3–6 до 8–15 с (змінити можна у «Ліміти»)")
                        s.migrated_delays = True
                except Exception as e:
                    log.error("Помилка config.json: %s", e)
            s.sanitize()
            if migrated:
                try:
                    s.save()
                except Exception as e:
                    log.error("Не вдалося зберегти config.json: %s", e)
            return s

    def sanitize(self) -> "Settings":
        """Валідовує конфігурацію з диска та UI, щоб помилкове значення не зупинило воркери."""
        defaults = Settings()

        def bounded_int(name: str, minimum: int, maximum: int) -> None:
            try:
                raw = getattr(self, name)
                if isinstance(raw, bool):
                    raise ValueError
                value = int(raw)
            except (TypeError, ValueError, OverflowError):
                value = getattr(defaults, name)
            setattr(self, name, min(maximum, max(minimum, value)))

        def bounded_float(name: str, minimum: float, maximum: float) -> None:
            try:
                value = float(getattr(self, name))
                if not math.isfinite(value):
                    raise ValueError
            except (TypeError, ValueError, OverflowError):
                value = getattr(defaults, name)
            setattr(self, name, min(maximum, max(minimum, value)))

        for name in ("main_username", "main_password", "main_sessionid", "main_proxy",
                     "scraper_username", "scraper_password", "scraper_sessionid", "scraper_proxy",
                     "web_user_agent", "accept_language", "timezone"):
            if not isinstance(getattr(self, name), str):
                setattr(self, name, getattr(defaults, name))

        for name in ("watch_stories", "like_last_post", "skip_business", "migrated_delays"):
            if not isinstance(getattr(self, name), bool):
                setattr(self, name, getattr(defaults, name))

        if isinstance(self.stop_words, list):
            self.stop_words = [word.strip()[:100] for word in self.stop_words
                               if isinstance(word, str) and word.strip()][:200]
        else:
            self.stop_words = list(DEFAULT_STOP_WORDS)

        for name, minimum, maximum in (
            ("likes_per_hour", 1, 500), ("likes_per_day", 1, 10000),
            ("stories_per_hour", 1, 1000), ("stories_per_day", 1, 10000),
            ("active_start_hour", 0, 23), ("active_end_hour", 1, 24),
            ("warmup_days", 0, 365), ("max_stories_per_user", 1, 100),
            ("break_every_min", 1, 1000), ("break_every_max", 1, 1000),
            ("max_throttle_streak", 1, 20), ("max_attempts_per_target", 1, 20),
            ("max_fail_streak", 1, 100), ("stale_processing_minutes", 1, 10080),
            ("idle_poll_seconds", 5, 3600), ("max_following", 0, 1000000),
            ("max_followers", 0, 10000000), ("min_followers", 0, 10000000),
            ("min_posts", 0, 1000000), ("scrape_amount", 1, 5000),
            ("scrape_break_every_min", 1, 1000), ("scrape_break_every_max", 1, 1000),
        ):
            bounded_int(name, minimum, maximum)

        for name, minimum, maximum in (
            ("request_delay_min", 0.1, 3600.0), ("request_delay_max", 0.1, 3600.0),
            ("pace_jitter", 0.0, 0.9), ("warmup_start_factor", 0.05, 1.0),
            ("like_probability", 0.0, 1.0), ("break_minutes_min", 0.0, 1440.0),
            ("break_minutes_max", 0.0, 1440.0), ("target_delay_min", 0.0, 3600.0),
            ("target_delay_max", 0.0, 3600.0), ("throttle_base_minutes", 0.1, 1440.0),
            ("block_cooldown_hours", 0.1, 720.0), ("scrape_delay_min", 0.5, 3600.0),
            ("scrape_delay_max", 0.5, 3600.0), ("scrape_break_minutes_min", 0.0, 1440.0),
            ("scrape_break_minutes_max", 0.0, 1440.0),
        ):
            bounded_float(name, minimum, maximum)

        for minimum_name, maximum_name in (
            ("request_delay_min", "request_delay_max"),
            ("target_delay_min", "target_delay_max"),
            ("break_minutes_min", "break_minutes_max"),
            ("scrape_delay_min", "scrape_delay_max"),
            ("scrape_break_minutes_min", "scrape_break_minutes_max"),
        ):
            setattr(self, maximum_name, max(getattr(self, minimum_name), getattr(self, maximum_name)))

        self.break_every_max = max(self.break_every_min, self.break_every_max)
        self.scrape_break_every_max = max(self.scrape_break_every_min, self.scrape_break_every_max)
        if self.active_end_hour <= self.active_start_hour:
            self.active_start_hour = defaults.active_start_hour
            self.active_end_hour = defaults.active_end_hour
        return self

    def save(self) -> None:
        with state.SETTINGS_LOCK:
            self.sanitize()
            p = state.APP_DIR / "config.json"
            temp = p.with_name(p.name + ".tmp")
            payload = json.dumps(
                {f.name: getattr(self, f.name) for f in fields(Settings)},
                ensure_ascii=False,
                indent=2,
            )
            temp.write_text(payload, encoding="utf-8")
            temp.replace(p)

