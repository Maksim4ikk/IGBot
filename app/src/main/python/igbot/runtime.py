from __future__ import annotations
import json
import logging
import time
from dataclasses import fields
from pathlib import Path
from urllib.parse import unquote
import requests
from . import state
from .database import Database
from .errors import ApiError, LoginRequired, PleaseWaitFewMinutes
from .instagram_client import WebInstaClient
from .settings import Settings
from .rate_limiter import RateLimiter
from .state import log

def init_android_env(files_dir: str) -> None:
    state.APP_DIR = Path(files_dir)
    state.APP_DIR.mkdir(parents=True, exist_ok=True)
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    if not any(isinstance(handler, state.MemoryLogHandler) for handler in root.handlers):
        handler = state.MemoryLogHandler()
        handler.setFormatter(logging.Formatter("%(asctime)s|%(levelname).1s|%(message)s", datefmt="%H:%M:%S"))
        root.addHandler(handler)
        logging.getLogger("urllib3").setLevel(logging.WARNING)
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

def get_dashboard_json() -> str:
    cfg = Settings.load()
    db = Database()
    counts = db.dashboard_counts()
    now = time.time()
    acc = cfg.main_username.strip()

    likes_today, likes_day_max, likes_hr, likes_hr_max = 0, cfg.likes_per_day, 0, cfg.likes_per_hour
    stories_today, stories_day_max, stories_hr, stories_hr_max = 0, cfg.stories_per_day, 0, cfg.stories_per_hour
    warmup = 1.0

    if acc:
        try:
            lim = RateLimiter(db, acc, cfg, state.STOP_WORKER)
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
        "worker_running": state.WORKER_RUNNING,
        "scraper_running": state.SCRAPER_RUNNING,
        "scraper_task": state.SCRAPER_TASK,
        "ready": counts["ready"],
        "interacted": counts["interacted"],
        "skipped": counts["skipped"],
        "errors": counts["errors"],
        "warmup": warmup,
        "likes_today": likes_today,
        "likes_day_max": likes_day_max,
        "likes_hr": likes_hr,
        "likes_hr_max": likes_hr_max,
        "stories_today": stories_today,
        "stories_day_max": stories_day_max,
        "stories_hr": stories_hr,
        "stories_hr_max": stories_hr_max,
        "last_event": state.LOG_BUFFER[-1] if state.LOG_BUFFER else "",
    }
    return json.dumps(payload, ensure_ascii=False)


def get_logs_json(limit: int = 300) -> str:
    """Останні рядки журналу формату «ГГ:ХХ:СС|рівень|текст» (від старих до нових)."""
    return json.dumps(list(state.LOG_BUFFER)[-int(limit):], ensure_ascii=False)


def clear_logs() -> None:
    state.LOG_BUFFER.clear()


def clear_counter(kind: str) -> str:
    """Очистити плитку «Огляду»: ready | interacted | skipped | errors. Повертає {"cleared": N}."""
    n = Database().clear_counter(kind)
    log.info("Очищено «%s»: %d", kind, n)
    return json.dumps({"cleared": n})


def import_targets_json(raw_json: str) -> str:
    """Імпорт черги з JSON, який зробила команда `igbot.py export` на ПК.
    Нові цілі додаються як 'ready' з готовим постом; наявні, які ще не оброблені
    успішно, оновлюються. Уже 'interacted' не чіпаємо."""
    data = json.loads(raw_json)
    items = data.get("items") if isinstance(data, dict) else data
    if not isinstance(items, list):
        raise ValueError("Невірний формат JSON: очікується список items")
    db = Database()
    now = time.time()
    added = skipped = 0
    for it in items:
        try:
            uid = int(it["user_id"])
            media_id = str(it["media_id"]).strip()
            media_code = str(it.get("media_code") or "").strip()
            username = str(it.get("username") or "").strip()
            source = str(it.get("source") or "import")
        except (KeyError, TypeError, ValueError, AttributeError):
            skipped += 1
            continue
        if not media_id:
            skipped += 1
            continue
        cur = db.conn.execute(
            """INSERT INTO targets
               (user_id, username, status, latest_media_id, latest_media_code, source, created_at, updated_at)
               VALUES (?,?,?,?,?,?,?,?)
               ON CONFLICT(user_id) DO UPDATE SET
                 status='ready', latest_media_id=excluded.latest_media_id,
                 latest_media_code=excluded.latest_media_code, attempts=0, last_error=NULL,
                 updated_at=excluded.updated_at
               WHERE targets.status IN ('ready','error','gone','skipped_dead','skipped_nothing','skipped_numbers')""",
            (uid, username, "ready", media_id, media_code, source, now, now))
        if cur.rowcount == 1:
            added += 1
        else:
            skipped += 1
    log.info("Імпорт з JSON: додано %d, пропущено %d (з %d)", added, skipped, len(items))
    return json.dumps({"added": added, "skipped": skipped, "total": len(items)}, ensure_ascii=False)


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
    n = db.conn.execute(
        "DELETE FROM targets WHERE status IN ('skipped_numbers','skipped_dead')"
    ).rowcount
    log.info("Видалено %d анкет для повторної перевірки", n)

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

    sessions = state.APP_DIR / "sessions"
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
        state.STOP_WORKER.set()
        cfg.main_username = ""
        cfg.main_password = ""
        cfg.main_sessionid = ""
    elif kind == "scraper":
        uname = cfg.scraper_username
        state.STOP_SCRAPER.set()
        cfg.scraper_username = ""
        cfg.scraper_password = ""
        cfg.scraper_sessionid = ""
    else:
        return

    if uname:
        try:
            (state.APP_DIR / "sessions" / f"{uname}.json").unlink()
        except FileNotFoundError:
            pass
    cfg.save()
    log.info("Вихід з акаунта @%s виконано", uname or "?")
