"""Мій профіль (основний акаунт): підписники, підписки, взаємність, підписатись / відписатись.

Усе робимо HTTP-клієнтом із сесією основного акаунта (так само, як парсер читає підписників донора).
Запити з WebView (fetch) для профілю та підписки/відписки Instagram віддавав як HTML-сторінку, тому тут їх немає.

Усі публічні функції повертають JSON-рядок — їх викликає Kotlin через BotEngine.
"""
from __future__ import annotations

import json
import re
import threading
import time
import traceback
from urllib.parse import quote, unquote

import requests

from . import state
from .errors import (
    ApiError, ChallengeRequired, FeedbackRequired, LoginRequired,
    PleaseWaitFewMinutes, StopWork, UserNotFound,
)
from .instagram_client import WebInstaClient
from .settings import Settings
from .state import log

CACHE_FILE = "friends_cache.json"
PAGE_SIZE = 100
MAX_PAGES = 800          # запобіжник від нескінченного циклу (≈80 000 акаунтів)
_USERNAME_RE = re.compile(r"[A-Za-z0-9._]{1,30}")

_REFRESH_LOCK = threading.Lock()   # одне оновлення списків за раз
_CACHE_LOCK = threading.Lock()     # короткий захист файлу кешу
PROGRESS = {"running": False, "phase": "", "done": 0, "total": 0}


def _j(obj) -> str:
    return json.dumps(obj, ensure_ascii=False)


# ── кеш ─────────────────────────────────────────────────────────────
def _cache_path():
    return state.APP_DIR / CACHE_FILE


def _read_cache():
    try:
        return json.loads(_cache_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _write_cache(data: dict) -> None:
    path = _cache_path()
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


# ── клієнт основного акаунта ────────────────────────────────────────
def _client(cfg: Settings):
    """HTTP-клієнт основного акаунта з його збереженою сесією. Повертає (клієнт, нікнейм, id)."""
    username = cfg.main_username.strip()
    sid = unquote(cfg.main_sessionid.strip())
    if not username or not sid:
        raise StopWork("Основний акаунт не підключено — увійдіть у вкладці «Акаунти»")
    if not _USERNAME_RE.fullmatch(username):
        raise StopWork("Некоректний нікнейм акаунта. Перепідключіть акаунт у вкладці «Акаунти».")

    cl = WebInstaClient(cfg.web_user_agent, cfg.accept_language)
    cl.delay_range = [1.0, 2.2]
    if cfg.main_proxy.strip():
        cl.set_proxy(cfg.main_proxy.strip())

    session_file = state.APP_DIR / "sessions" / f"{username}.json"
    if session_file.exists():
        try:
            cl.load_settings(session_file)
        except Exception as e:
            log.warning("[Профіль] Не вдалося прочитати збережену сесію (%s)", e)
    cl.session.cookies.set("sessionid", sid, domain=".instagram.com")
    if ":" in sid:
        cl.session.cookies.set("ds_user_id", sid.split(":")[0], domain=".instagram.com")
    if not cl._cookie("csrftoken"):
        cl._bootstrap_csrf()

    uid = cl._cookie("ds_user_id")
    if not uid.isdigit():
        raise StopWork("Немає ds_user_id — перепідключіть акаунт у вкладці «Акаунти»")
    return cl, username, uid


def _explain(e: Exception) -> str:
    if isinstance(e, PleaseWaitFewMinutes):
        return f"Instagram просить зачекати (429): {e}. Спробуйте за кілька хвилин."
    if isinstance(e, (LoginRequired, ChallengeRequired)):
        return f"Сесія недійсна: {e}. Увійдіть знову у вкладці «Акаунти»."
    if isinstance(e, FeedbackRequired):
        return f"Instagram заблокував дію: {e}"
    if isinstance(e, StopWork):
        return str(e)
    if isinstance(e, (ApiError, UserNotFound)):
        return f"Помилка Instagram: {e}"
    if isinstance(e, requests.RequestException):
        return f"Помилка мережі: {e}"
    traceback.print_exc()
    return f"{type(e).__name__}: {e}"


# ── профіль і списки ────────────────────────────────────────────────
def _profile_info(cl: WebInstaClient, uid: str) -> dict:
    """Ім'я, опис, аватар і лічильники власного профілю. Порожній dict, якщо Instagram не віддав (напр. 429)."""
    try:
        data = cl._req("GET", f"{cl.API}/users/{uid}/info/", context="дані профілю", quiet_html=True)
        user = data.get("user") or {}
        if not user:
            return {}
        pic = ((user.get("hd_profile_pic_url_info") or {}).get("url")) or user.get("profile_pic_url") or ""
        return {
            "full_name": user.get("full_name") or "",
            "bio": user.get("biography") or "",
            "pic": pic,
            "followers_count": int(user.get("follower_count", -1) if user.get("follower_count") is not None else -1),
            "following_count": int(user.get("following_count", -1) if user.get("following_count") is not None else -1),
        }
    except (PleaseWaitFewMinutes, ApiError, UserNotFound, LoginRequired, requests.RequestException) as e:
        log.warning("[Профіль] Дані профілю не отримано (%s) — покажу лише лічильники зі списків", e)
        return {}


def _fetch_users(cl: WebInstaClient, uid: str, kind: str):
    """Повний список підписників/підписок. kind: followers | following.
    Повертає ([[pk, username, full_name], ...], дочитано_до_кінця)."""
    out, seen, max_id = [], set(), ""
    for _ in range(MAX_PAGES):
        url = f"{cl.API}/friendships/{uid}/{kind}/?count={PAGE_SIZE}"
        if max_id:
            url += f"&max_id={max_id}"
        data = cl._req("GET", url, context=f"список {kind}")
        users = data.get("users") or []
        for u in users:
            pk = int(u["pk"])
            if pk in seen:
                continue
            seen.add(pk)
            out.append([pk, u.get("username", ""), u.get("full_name", "") or ""])
        PROGRESS["done"] = len(out)
        nxt = data.get("next_max_id") or ""
        if not nxt:
            return out, True
        if not users:        # курсор є, а людей немає — список вважаємо недочитаним
            return out, False
        max_id = str(nxt)
    return out, False


def _check(label: str, got: int, expected: int, finished: bool) -> str:
    if not finished:
        return f"{label}: Instagram віддав список не до кінця (отримано {got})"
    if expected >= 0 and abs(got - expected) > max(5, int(expected * 0.03)):
        return f"{label}: отримано {got} із {expected}"
    return ""


def friends_refresh() -> str:
    """Завантажити профіль + повні списки підписників/підписок і зберегти в кеш. {"ok": true} або {"ok": false, "error": ...}."""
    if not _REFRESH_LOCK.acquire(blocking=False):
        return _j({"ok": False, "error": "Оновлення вже виконується"})
    try:
        PROGRESS.update(running=True, phase="Профіль", done=0, total=0)
        cfg = Settings.load()
        cl, username, uid = _client(cfg)
        log.info("[Профіль] Оновлюю підписників і підписки @%s...", username)

        head = _profile_info(cl, uid)
        f_expected = int(head.get("followers_count", -1))
        g_expected = int(head.get("following_count", -1))

        PROGRESS.update(phase="Підписники", done=0, total=max(f_expected, 0))
        followers, f_done = _fetch_users(cl, uid, "followers")
        PROGRESS.update(phase="Підписки", done=0, total=max(g_expected, 0))
        following, g_done = _fetch_users(cl, uid, "following")

        warnings = [w for w in (
            _check("Підписники", len(followers), f_expected, f_done),
            _check("Підписки", len(following), g_expected, g_done),
        ) if w]

        snap = {
            "username": username,
            "full_name": head.get("full_name", ""),
            "bio": head.get("bio", ""),
            "pic": head.get("pic", ""),
            "followers_count": f_expected if f_expected >= 0 else len(followers),
            "following_count": g_expected if g_expected >= 0 else len(following),
            "followers": followers,
            "following": following,
            "complete": not warnings,
            "warning": "; ".join(warnings),
            "updated_at": int(time.time()),
        }
        with _CACHE_LOCK:
            _write_cache(snap)
        log.info("[Профіль] @%s: підписників %d, підписок %d%s", username, len(followers), len(following),
                 "" if not warnings else f" (увага: {snap['warning']})")
        return _j({"ok": True})
    except Exception as e:
        msg = _explain(e)
        log.error("[Профіль] Не вдалося оновити: %s", msg)
        return _j({"ok": False, "error": msg})
    finally:
        PROGRESS.update(running=False, phase="", done=0, total=0)
        _REFRESH_LOCK.release()


def friends_get_cached() -> str:
    """Останній збережений знімок для поточного основного акаунта або порожній рядок."""
    cfg = Settings.load()
    with _CACHE_LOCK:
        data = _read_cache()
    if not data or data.get("username") != cfg.main_username.strip():
        return ""
    return _j(data)


def friends_progress() -> str:
    return _j(PROGRESS)



# ── підписатись / відписатись ───────────────────────────────────────
def _interpret(action: str, data: dict) -> dict:
    """Відповідь Instagram (JSON) -> {"status": ok | requested | error, "detail": ...}."""
    if data.get("status") != "ok":
        return {"status": "error", "detail": str(data)[:160]}
    fs = data.get("friendship_status") or {}
    if action == "unfollow":
        if fs.get("following") is True:
            return {"status": "error", "detail": "Instagram не підтвердив відписку"}
        return {"status": "ok", "detail": ""}
    if fs.get("outgoing_request"):
        return {"status": "requested", "detail": "Акаунт приватний — запит надіслано"}
    if fs and fs.get("following") is False:
        return {"status": "error", "detail": "Instagram не підтвердив підписку"}
    return {"status": "ok", "detail": ""}


def _update_cache(username: str, action: str, pk: int, user_name: str) -> None:
    with _CACHE_LOCK:
        data = _read_cache()
        if not data or data.get("username") != username:
            return
        following = data.get("following") or []
        if action == "unfollow":
            kept = [r for r in following if r[0] != pk]
            if len(kept) != len(following):
                data["following_count"] = max(0, int(data.get("following_count", len(following))) - 1)
            data["following"] = kept
        elif not any(r[0] == pk for r in following):
            full = next((r[2] for r in (data.get("followers") or []) if r[0] == pk), "")
            following.insert(0, [pk, user_name, full])
            data["following"] = following
            data["following_count"] = int(data.get("following_count", len(following) - 1)) + 1
        _write_cache(data)


def friends_action(action: str, pk: int, username: str = "") -> str:
    """action: follow | unfollow.
    Повертає {"status": ok | requested | blocked | rate_limited | login | error, "detail": ...}."""
    who = f"@{username}" if username else str(pk)
    title = "Відписка" if action == "unfollow" else "Підписка"
    try:
        if action not in ("follow", "unfollow"):
            raise ValueError(f"невідома дія {action}")
        pk = int(pk)
        cfg = Settings.load()
        cl, me, _uid = _client(cfg)
        endpoint = "destroy" if action == "unfollow" else "create"
        try:
            data = cl._req(
                "POST", f"{cl.API}/friendships/{endpoint}/{pk}/",
                delay=(0.3, 0.8), context=f"{title.lower()} {who}",
                headers={"X-Instagram-AJAX": "1", "Content-Type": "application/x-www-form-urlencoded"},
                data={"container_module": "profile", "user_id": str(pk)},
            )
            out = _interpret(action, data)
        except PleaseWaitFewMinutes as e:
            out = {"status": "rate_limited", "detail": str(e)}
        except (FeedbackRequired, ChallengeRequired) as e:
            out = {"status": "blocked", "detail": str(e)}
        except LoginRequired as e:
            out = {"status": "login", "detail": str(e)}
        except (ApiError, UserNotFound) as e:
            out = {"status": "error", "detail": str(e)}

        if out["status"] == "ok":
            _update_cache(me, action, pk, username)
            log.info("[Профіль] %s: %s — готово", title, who)
        else:
            log.warning("[Профіль] %s: %s — %s (%s)", title, who, out["status"], out["detail"])
        return _j(out)
    except StopWork as e:
        return _j({"status": "login", "detail": str(e)})
    except Exception as e:
        return _j({"status": "error", "detail": _explain(e)})