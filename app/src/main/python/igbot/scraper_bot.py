"""Парсер аудиторії: збирає кандидатів (лайкери поста, підписники, хештег) і знаходить їм свіжий пост.

Профілі відкриваються у WebView (див. webbridge.py), бо запити профілів через API Instagram блокує.
"""
from __future__ import annotations
import hashlib, random
from collections import Counter
from datetime import datetime, timezone
from .base_bot import BaseBot
from .database import Database
from .errors import ApiError, StopWork, UserNotFound
from .models import MediaItem, UserShort
from .settings import Settings
from .state import log, STOP_SCRAPER
from .utils import (
    build_stop_regex, freshest_code, human_pause, media_pk_from_url, nap, shortcode_to_pk,
)

# Зрозумілі підписи статусів для журналу (в БД лишаються технічні назви)
STATUS_TEXT = {
    "ready": "готово (є пост)",
    "skipped_private": "приватний акаунт — пропущено",
    "skipped_dead": "немає постів або недоступний — пропущено",
    "skipped_commercial": "магазин/бізнес — пропущено",
    "skipped_numbers": "не підходить за цифрами — пропущено",
}


class ScraperBot(BaseBot):
    delay_override = [3.0, 6.0]

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
        self.preset_medias: dict[int, MediaItem] = {}  # хештег: пост відомий заздалегідь

    # ── точка входу ─────────────────────────────────────────────────
    def scrape(self, source_type: str, target: str, amount: int = 0) -> None:
        amount = int(amount) or int(self.cfg.scrape_amount)
        target = target.strip()
        if source_type == "followers":
            self._scrape_followers(target.lstrip("@"), amount)
        elif source_type == "likers":
            self._scrape_likers(target, amount)
        elif source_type == "hashtag":
            self._scrape_hashtag(target.lstrip("#"), amount)
        else:
            raise StopWork(f"Невідоме джерело: {source_type}")

    # ── джерела ─────────────────────────────────────────────────────
    def _scrape_likers(self, url: str, amount: int) -> None:
        try:
            pk = media_pk_from_url(url)
        except ValueError as e:
            raise StopWork(str(e))
        log.info("[Парсер] Завантажую лайкерів поста...")
        likers = self.api(self.cl.media_likers, pk)
        self._process(likers[:amount], f"likers:{pk}")

    def _scrape_followers(self, uname: str, amount: int) -> None:
        log.info("[Парсер] Шукаю ID донора @%s...", uname)
        uid = self._resolve_user_id(uname)
        log.info("[Парсер] ID @%s = %d. Завантажую підписників (до %d)...", uname, uid, amount)
        try:
            followers, _ = self.api(self.cl.user_followers_v1_chunk, uid, max_amount=amount)
        except (ApiError, UserNotFound) as e:
            raise StopWork(f"Не вдалося отримати підписників @{uname}: {e}")
        if not followers:
            raise StopWork(f"Instagram повернув порожній список підписників @{uname} "
                           "(акаунт приватний або список недоступний)")
        log.info("[Парсер] Отримано %d підписників", len(followers))
        self._process(followers, f"followers:{uname}")

    def _resolve_user_id(self, uname: str) -> int:
        """Числовий ID акаунта зі сторінки профілю (пошук /web/search/topsearch/ повертає 404)."""
        res = self.web.profile_id(uname)
        if res.ok:
            return int(res.get("id"))
        if res.status == "not_found":
            raise StopWork(f"Акаунт @{uname} не знайдено")
        raise StopWork(f"Не вдалося визначити ID @{uname}: {res.status} ({res.detail})")

    def _scrape_hashtag(self, tag: str, amount: int) -> None:
        """Сторінка тегу -> пости -> автор кожного поста. Лайкатимемо саме цей пост (він свіжий).
        Приватних тут не буває: у тегах лише публічні пости."""
        log.info("[Парсер] Відкриваю хештег #%s у WebView...", tag)
        res = self.web.hashtag_posts(tag, amount)
        codes = res.list("codes")[:amount]
        if not codes:
            raise StopWork(f"Хештег #{tag}: постів не знайдено ({res.status}: {res.detail})")
        log.info("[Парсер] Знайдено постів за #%s: %d. Визначаю авторів (~10 с на пост)...", tag, len(codes))

        cands: list[UserShort] = []
        seen_names: set[str] = set()
        fails = 0
        for i, code in enumerate(codes, 1):
            if self.stop_event.is_set():
                break
            r = self.web.post_author(code)
            uname = r.get("username").strip()
            if not r.ok or not uname:
                fails += 1
                log.warning("[Парсер] [%d/%d] пост %s: автора не визначено (%s: %s)",
                            i, len(codes), code, r.status, r.detail)
                if fails >= 5:
                    raise StopWork("Не вдалося визначити авторів 5 постів поспіль — зупиняю парсер.")
                human_pause(self.cfg.scrape_delay_min, self.cfg.scrape_delay_max, self.stop_event)
                continue
            fails = 0
            if uname.lower() in seen_names:
                continue
            seen_names.add(uname.lower())

            try:
                mpk = shortcode_to_pk(code)
            except ValueError:
                continue
            uid = None
            for s in r.get("ids").split(","):
                s = s.strip()
                if s.isdigit() and len(s) <= 14 and int(s) != mpk:  # ID людей коротші за ID постів
                    uid = int(s)
                    break
            if uid is None:  # справжній ID не знайшли — стабільний від'ємний ідентифікатор за нікнеймом
                uid = -int(hashlib.sha1(uname.lower().encode("utf-8")).hexdigest()[:12], 16)
            user = UserShort(pk=uid, username=uname, full_name="", is_private=False)
            self.preset_medias[uid] = MediaItem(
                id=str(mpk), pk=mpk, code=code, taken_at=datetime.now(tz=timezone.utc), user=user)
            cands.append(user)
            log.info("[Парсер] [%d/%d] пост %s -> @%s", i, len(codes), code, uname)
            human_pause(self.cfg.scrape_delay_min, self.cfg.scrape_delay_max, self.stop_event)

        self._process(cands, f"hashtag:{tag}")

    # ── перевірка кандидата ─────────────────────────────────────────
    def _evaluate(self, uid: int, uname: str):
        """Повертає (нікнейм, статус, пост). Помилки (не вдалося прочитати сторінку) — ApiError:
        такі анкети не записуються в БД, щоб їх можна було перевірити пізніше."""
        preset = self.preset_medias.get(uid)
        if preset is not None:
            return uname, "ready", preset
        if self.stop_event.is_set():
            raise StopWork("Зупинено користувачем")
        res = self.web.latest_posts(uname)
        if res.ok:
            code = freshest_code(res.list("codes"))
            if not code:
                raise ApiError("WebView повернув порожній список постів")
            pk = shortcode_to_pk(code)
            return uname, "ready", MediaItem(
                id=str(pk), pk=pk, code=code, taken_at=datetime.now(tz=timezone.utc))
        if res.status == "private":
            return uname, "skipped_private", None
        if res.status in ("no_posts", "not_found"):
            log.info("[Парсер] @%s: %s (%s)", uname, res.status, res.detail)
            return uname, "skipped_dead", None
        raise ApiError(f"WebView: {res.status} {res.detail}")

    def _next_break(self) -> int:
        return random.randint(self.cfg.scrape_break_every_min, self.cfg.scrape_break_every_max)

    def _process(self, cands: list[UserShort], source: str) -> None:
        new = [u for u in cands if not self.db.exists(u.pk)]
        log.info("[Парсер] Отримано %d анкет (нових: %d)", len(cands), len(new))
        log.info("[Парсер] Відкриваю профілі у WebView та беру найсвіжіший пост (~10–20 с на анкету)")
        counter = Counter()
        since_break = 0
        fail_streak = 0
        next_break = self._next_break()

        try:
            for i, u in enumerate(new, 1):
                if self.stop_event.is_set():
                    break
                uid, uname = u.pk, u.username

                # 1. Миттєві відсіювання без жодного запиту
                if u.is_private is True:
                    self.db.add_target(uid, uname, "skipped_private", source)
                    counter["skipped_private"] += 1
                    log.info("[Парсер] [%d/%d] @%s -> %s", i, len(new), uname, STATUS_TEXT["skipped_private"])
                    continue
                if self.stop_re and self.stop_re.search(f"{uname} {u.full_name}"):
                    self.db.add_target(uid, uname, "skipped_commercial", source)
                    counter["skipped_commercial"] += 1
                    log.info("[Парсер] [%d/%d] @%s -> %s (за назвою)", i, len(new), uname,
                             STATUS_TEXT["skipped_commercial"])
                    continue

                # 2. Відкриваємо профіль
                try:
                    name, status, media = self._evaluate(uid, uname)
                    fail_streak = 0
                except UserNotFound as e:
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
                        raise StopWork("5 помилок поспіль — зупиняю парсер.")
                    human_pause(4, 8, self.stop_event)
                    continue

                self.db.add_target(uid, name, status, source,
                                   str(media.id) if media else None, str(media.code) if media else None)
                counter[status] += 1
                log.info("[Парсер] [%d/%d] @%s -> %s", i, len(new), name, STATUS_TEXT.get(status, status))

                # 3. Паузи (для відомого заздалегідь поста запиту не було — пауза не потрібна)
                if self.preset_medias.get(uid) is not None:
                    continue
                since_break += 1
                if since_break >= next_break and i < len(new):
                    mins = random.uniform(self.cfg.scrape_break_minutes_min, self.cfg.scrape_break_minutes_max)
                    log.info("[Парсер] Перерва %.1f хв...", mins)
                    nap(mins * 60, self.stop_event)
                    since_break = 0
                    next_break = self._next_break()
                else:
                    human_pause(self.cfg.scrape_delay_min, self.cfg.scrape_delay_max, self.stop_event)
        finally:
            self.save_session()
            log.info(
                "[Парсер] Завершено! готово: %d | приватних: %d | комерції: %d | без постів/недоступні: %d | помилок: %d",
                counter["ready"], counter["skipped_private"], counter["skipped_commercial"],
                counter["skipped_numbers"] + counter["skipped_dead"], counter["error"],
            )
