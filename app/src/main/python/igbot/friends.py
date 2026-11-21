"""Мій профіль (основний акаунт): підписники, підписки, взаємність, підписатись / відписатись.

Списки та дані профілю читаємо HTTP-клієнтом із сесією основного акаунта (так само, як парсер читає
підписників донора). Підписку/відписку виконує WebView (com.maks.igbot.web.WebApi): відкриває сторінку
профілю й натискає кнопки, як людина — так само працює лайк. Запити до /api/v1 зсередини WebView
(fetch) Instagram віддавав як HTML-сторінку / 404, тому їх тут немає. Якщо на сторінці не знайшлося
кнопки, дію ще раз пробуємо HTTP-запитом.

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

WEB_API_CLASS = "com.maks.igbot.web.WebApi"
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
    Повертає ([[pk, username, full_name, profile_pic_url], ...], дочитано_до_кінця)."""
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
            out.append([
                pk,
                u.get("username", ""),
                u.get("full_name", "") or "",
                u.get("profile_pic_url", "") or "",
            ])
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
            "avatar_schema_version": 1,
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
    # Older caches only stored usernames and cannot display list avatars.
    if data.get("avatar_schema_version") != 1:
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
            follower = next((r for r in (data.get("followers") or []) if r[0] == pk), [])
            full = follower[2] if len(follower) > 2 else ""
            pic = follower[3] if len(follower) > 3 else ""
            following.insert(0, [pk, user_name, full, pic])
            data["following"] = following
            data["following_count"] = int(data.get("following_count", len(following) - 1)) + 1
        _write_cache(data)


def _web_friend_action(action: str, username: str, cl: WebInstaClient, cfg: Settings) -> dict:
    """Підписка / відписка через WebView: відкриває профіль @username і натискає кнопки.
    Повертає {"status": ok|requested|blocked|login|not_found|no_button|not_confirmed|timeout|error, "detail": ...}."""
    try:
        from java import jclass  # доступно лише в Chaquopy (Android)
    except ImportError as e:
        raise StopWork("Ця функція працює лише в Android-застосунку") from e
    cookies = json.dumps({c.name: c.value for c in cl.session.cookies if c.value})
    try:
        raw = jclass(WEB_API_CLASS).friendAction(action, username, (cfg.web_user_agent or "").strip(), cookies)
    except UnicodeError as e:  # напр. обрізаний посередині емодзі в тексті сторінки
        return {"status": "error", "detail": f"некоректний текст від WebView: {e}"}
    try:
        data = json.loads(str(raw))
        return data if isinstance(data, dict) else {"status": "error", "detail": "несподівана відповідь"}
    except ValueError:
        return {"status": "error", "detail": "некоректна відповідь WebView"}


def _api_action(cl: WebInstaClient, action: str, pk: int, who: str, title: str) -> dict:
    """Запасний шлях: той самий запит, що робить сайт (HTTP-клієнт із сесією акаунта)."""
    endpoint = "destroy" if action == "unfollow" else "create"
    try:
        data = cl._req(
            "POST", f"{cl.API}/friendships/{endpoint}/{pk}/",
            delay=(0.3, 0.8), context=f"{title.lower()} {who}",
            headers={"X-Instagram-AJAX": "1", "Content-Type": "application/x-www-form-urlencoded"},
            data={"container_module": "profile", "user_id": str(pk)},
        )
        return _interpret(action, data)
    except PleaseWaitFewMinutes as e:
        return {"status": "rate_limited", "detail": str(e)}
    except (FeedbackRequired, ChallengeRequired) as e:
        return {"status": "blocked", "detail": str(e)}
    except LoginRequired as e:
        return {"status": "login", "detail": str(e)}
    except (ApiError, UserNotFound) as e:
        return {"status": "error", "detail": str(e)}


def friends_action(action: str, pk: int, username: str = "") -> str:
    """action: follow | unfollow.
    Повертає {"status": ok | requested | blocked | rate_limited | login | error, "detail": ...}.
    Спершу дію робить WebView на сторінці профілю; лише якщо кнопки там не знайшлося — HTTP-запит."""
    who = f"@{username}" if username else str(pk)
    title = "Відписка" if action == "unfollow" else "Підписка"
    try:
        if action not in ("follow", "unfollow"):
            raise ValueError(f"невідома дія {action}")
        pk = int(pk)
        username = (username or "").strip().lstrip("@")
        cfg = Settings.load()
        cl, me, _uid = _client(cfg)

        out = None
        if _USERNAME_RE.fullmatch(username):
            res = _web_friend_action(action, username, cl, cfg)
            status, detail = str(res.get("status", "error")), str(res.get("detail", ""))
            if status in ("ok", "requested", "blocked", "login"):
                out = {"status": status, "detail": detail}
            elif status in ("no_button", "timeout"):
                log.info("[Профіль] %s: %s — на сторінці кнопки не знайдено (%s: %s), пробую через API",
                         title, who, status, detail[:200])
            else:
                out = {"status": "error", "detail": f"{status}: {detail}"[:300]}
        if out is None:
            out = _api_action(cl, action, pk, who, title)

        if out["status"] == "ok":
            _update_cache(me, action, pk, username)
            log.info("[Профіль] %s: %s — готово%s", title, who, " (так і було)" if out["detail"] == "already" else "")
        elif out["status"] == "requested":
            log.info("[Профіль] %s: %s — приватний акаунт, запит надіслано", title, who)
        else:
            log.warning("[Профіль] %s: %s — %s (%s)", title, who, out["status"], out["detail"])
        return _j(out)
    except StopWork as e:
        return _j({"status": "login", "detail": str(e)})
    except Exception as e:
        return _j({"status": "error", "detail": _explain(e)})
