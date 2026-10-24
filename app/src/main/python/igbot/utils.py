from __future__ import annotations
import random, re, threading, time
from datetime import datetime, timezone
from typing import Optional
from .models import MediaItem
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

