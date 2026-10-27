from __future__ import annotations

import json
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
    scrape_break_every_min: int = 35
    scrape_break_every_max: int = 50
    scrape_break_minutes_min: float = 2.0
    scrape_break_minutes_max: float = 4.0
    stop_words: list = field(default_factory=lambda: list(DEFAULT_STOP_WORDS))

    @classmethod
    def load(cls) -> "Settings":
        s = cls()
        p = state.APP_DIR / "config.json"
        if p.exists():
            try:
                data = json.loads(p.read_text(encoding="utf-8"))
                valid = {f.name for f in fields(cls)}
                for k, v in data.items():
                    if k in valid:
                        setattr(s, k, v)
            except Exception as e:
                log.error("Помилка config.json: %s", e)
        return s.sanitize()

    def sanitize(self) -> "Settings":
        """Виправляє некоректні значення (мін > макс, нуль, сміття), щоб бот не впав посеред роботи."""
        defaults = Settings()
        try:
            self.scrape_amount = max(1, int(self.scrape_amount))
            self.scrape_delay_min = max(0.5, float(self.scrape_delay_min))
            self.scrape_delay_max = max(self.scrape_delay_min, float(self.scrape_delay_max))
            self.scrape_break_every_min = max(1, int(self.scrape_break_every_min))
            self.scrape_break_every_max = max(self.scrape_break_every_min, int(self.scrape_break_every_max))
            self.scrape_break_minutes_min = max(0.0, float(self.scrape_break_minutes_min))
            self.scrape_break_minutes_max = max(self.scrape_break_minutes_min, float(self.scrape_break_minutes_max))
        except (TypeError, ValueError):
            for name in ("scrape_amount", "scrape_delay_min", "scrape_delay_max", "scrape_break_every_min",
                         "scrape_break_every_max", "scrape_break_minutes_min", "scrape_break_minutes_max"):
                setattr(self, name, getattr(defaults, name))
        return self

    def save(self) -> None:
        p = state.APP_DIR / "config.json"
        p.write_text(
            json.dumps({f.name: getattr(self, f.name) for f in fields(Settings)},
                       ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

