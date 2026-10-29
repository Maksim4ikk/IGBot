from __future__ import annotations
import hashlib
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


_SHORTCODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"


def shortcode_to_pk(code: str) -> int:
    """Код поста з посилання (DewhcNrjtfV) -> числовий ID поста. ValueError при невірному символі."""
    pk = 0
    for ch in code:
        pk = pk * 64 + _SHORTCODE_ALPHABET.index(ch)
    return pk


def pk_to_shortcode(pk: int) -> str:
    """Числовий ID поста -> код для посилання."""
    pk = int(pk)
    if pk <= 0:
        raise ValueError("pk має бути додатним")
    out = ""
    while pk:
        pk, r = divmod(pk, 64)
        out = _SHORTCODE_ALPHABET[r] + out
    return out


def shortcode_from_url(url: str) -> str:
    """Код поста з посилання (https://www.instagram.com/p/DewhcNrjtfV/?x=1 -> DewhcNrjtfV)."""
    m = re.search(r"/(?:p|reel|tv)/([A-Za-z0-9_-]+)", url)
    if not m:
        raise ValueError("Невірне посилання на пост")
    return m.group(1)


def media_pk_from_url(url: str) -> int:
    return shortcode_to_pk(shortcode_from_url(url))


def pseudo_user_id(username: str) -> int:
    """Стабільний від'ємний ID для людини, чий справжній числовий ID невідомий (читали зі сторінки).
    Від'ємний, щоб не збігтися зі справжніми ID; дублікати з справжніми ID відсікає перевірка за нікнеймом."""
    return -int(hashlib.sha1(username.lower().encode("utf-8")).hexdigest()[:12], 16)


def freshest_code(codes) -> Optional[str]:
    """Із кількох кодів постів вибирає найсвіжіший: ID постів зростають з часом публікації,
    тому закріплені старі пости не заважають."""
    best, best_pk = None, -1
    for c in codes:
        try:
            pk = shortcode_to_pk(str(c).strip())
        except ValueError:
            continue
        if pk > best_pk:
            best, best_pk = str(c).strip(), pk
    return best
