from __future__ import annotations
import random, time
from datetime import datetime, timedelta
from threading import Event
from typing import Optional
from . import state
from .database import Database
from .settings import Settings
from .utils import fmt_secs, get_local_tz, nap
from .state import log
class RateLimiter:
    def __init__(self, db: Database, account: str, cfg: Settings, stop_event: Event):
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

