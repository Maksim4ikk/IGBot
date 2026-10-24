from __future__ import annotations
import json, logging, random, re, threading, time
from collections import Counter
from typing import Callable, Optional
from urllib.parse import unquote
import requests
from . import state
from .database import Database
from .errors import (
    ApiError, ChallengeRequired, FeedbackRequired, LoginRequired,
    MediaNotFound, PleaseWaitFewMinutes, StopWork, UserNotFound,
)
from .instagram_client import WebInstaClient
from .models import MediaItem, UserShort
from .rate_limiter import RateLimiter
from .settings import Settings
from .state import log, STOP_SCRAPER, STOP_WORKER
from .utils import build_stop_regex, fmt_secs, human_pause, nap, pick_latest


class BaseBot:
    delay_override: Optional[list] = None   # перевизначається в нащадках
    # Валідацію сесії виконує перший робочий запит, без окремого /users/{id}/info/.
    verify_session_on_start = False

    def __init__(self, cfg: Settings, db: Database, username: str, password: str,
                 stop_event: threading.Event, proxy: str = "", sessionid: str = ""):
        self.cfg, self.db = cfg, db
        self.username, self.password = username.strip(), password.strip()
        self.proxy = proxy.strip()
        self.sessionid = sessionid.strip()
        self.stop_event = stop_event
        if not self.username:
            raise StopWork("Вкажіть логін акаунта у вкладці 'Акаунти'!")
        self.session_path = state.APP_DIR / "sessions" / f"{self.username}.json"
        try:
            self.throttle_streak = int(db.get_meta(f"rate_limit_streak:{self.username}") or "0")
        except (TypeError, ValueError):
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
        # Cooldown зберігається в БД: перезапуск застосунку чи парсера
        # не повинен скидати паузу та одразу повторювати запит до Instagram.
        raw_until = self.db.get_meta(f"rate_limit_until:{self.username}")
        try:
            until = float(raw_until or "0")
        except (TypeError, ValueError):
            until = 0
        if until > time.time():
            raise StopWork(
                f"@{self.username} на паузі після 429 ще {fmt_secs(until - time.time())}"
            )

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
                if self.verify_session_on_start:
                    self.cl._bootstrap_csrf()
                    self.api(self.cl.verify_session)
                log.info("[%s] Сесію завантажено!", self.username)
                return
            except StopWork:
                raise
            except Exception as e:
                log.warning("[%s] Збережена сесія не підійшла (%s), новий вхід...", self.username, e)
        self._fresh_login()

    def _fresh_login(self, verify: bool = True) -> None:
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

        # Перевірка робиться через той самий retry/backoff, що й запити парсера.
        # Під час повторної авторизації api() сам повторить початковий запит.
        if verify and self.verify_session_on_start:
            self.api(self.cl.verify_session)

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
                self.db.set_meta(f"rate_limit_streak:{self.username}", "0")
                self.db.set_meta(f"rate_limit_until:{self.username}", "0")
                return res
            except LoginRequired as e:
                if relogged:
                    raise StopWork(f"Сесія відхилена ({e}). Оновіть sessionid у вкладці Акаунти.")
                relogged = True
                log.warning("[%s] Повторна авторизація (%s)...", self.username, e)
                self._fresh_login(verify=False)
            except FeedbackRequired as e:
                self._set_block(f"Action Block ({e})")
                raise StopWork("Спіймано Action Block!")
            except ChallengeRequired as e:
                raise StopWork(str(e))
            except PleaseWaitFewMinutes as e:
                self.throttle_streak += 1
                self.db.set_meta(
                    f"rate_limit_streak:{self.username}", str(self.throttle_streak)
                )
                if self.throttle_streak > self.cfg.max_throttle_streak:
                    self._set_block("Троттлінг запитів (429)")
                    raise StopWork(
                        "Instagram продовжує повертати 429. Запити зупинено; "
                        f"акаунт на паузі {self.cfg.block_cooldown_hours:g} год."
                    )
                wait = min(
                    max(
                        self.cfg.throttle_base_minutes * 60 * 2 ** (self.throttle_streak - 1),
                        e.retry_after or 0,
                        ),
                    3600,
                )
                self.db.set_meta(
                    f"rate_limit_until:{self.username}", str(time.time() + wait)
                )
                log.warning("[%s] Ліміт запитів (%s). Пауза %s...", self.username, e, fmt_secs(wait))
                nap(wait, self.stop_event)
            except requests.RequestException as e:
                net_tries += 1
                if net_tries > 3:
                    raise StopWork(f"Помилка мережі: {e}")
                nap(15 * net_tries, self.stop_event)


class ScraperBot(BaseBot):
    delay_override = [5.0, 10.0]

    def __init__(self, cfg: Settings, db: Database):
        # Автоматично очищаємо попередні блоки парсера при старті,
        # щоб помилковий Action Block або 429 на /info/ не блокував роботу
        uname = cfg.scraper_username.strip()
        if uname:
            db.set_meta(f"blocked_until:{uname}", "0")
            db.set_meta(f"rate_limit_until:{uname}", "0")
            db.set_meta(f"rate_limit_streak:{uname}", "0")

        super().__init__(
            cfg, db,
            username=cfg.scraper_username,
            password=cfg.scraper_password,
            stop_event=STOP_SCRAPER,
            proxy=cfg.scraper_proxy,
            sessionid=cfg.scraper_sessionid,
        )
        self.stop_re = build_stop_regex(self.cfg.stop_words)
        self.fast_mode = False
        self.preset_medias: dict[int, MediaItem] = {}

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
            self._process(likers[:amount], f"likers:{pk}")
        elif source_type == "hashtag":
            tag = target.lstrip("#")
            log.info("[Парсер] Завантажую пости за хештегом #%s...", tag)
            medias = self.api(self.cl.hashtag_medias_recent, tag, amount=amount)
            seen: dict[int, UserShort] = {}
            for m in medias:
                if m.user and m.user.pk not in seen:
                    seen[m.user.pk] = m.user
                    self.preset_medias[m.user.pk] = m
            self._process(list(seen.values()), f"hashtag:{tag}")

    def _evaluate(self, uid: int, uname: str):
        preset_media = self.preset_medias.get(uid)
        if self.fast_mode:
            return uname, "ready", preset_media

        c = self.cfg
        try:
            info = self.cl.user_info(uid)
        except (PleaseWaitFewMinutes, FeedbackRequired, ApiError) as e:
            self.fast_mode = True
            log.warning(
                "[Парсер] Instagram обмежив детальні запити анкет (%s) — вмикаю безпечний режим швидкого збереження!",
                e,
            )
            return uname, "ready", preset_media

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

        if preset_media is not None:
            return name, "ready", preset_media

        try:
            latest = pick_latest(self.cl.user_medias(uid, amount=4, username=name))
        except (PleaseWaitFewMinutes, FeedbackRequired, ApiError) as e:
            self.fast_mode = True
            log.warning(
                "[Парсер] Ліміт на стрічку постів (%s) — зберігаю анкети у швидкому режимі!",
                e,
            )
            return name, "ready", None

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

                # У швидкому режимі запити до Instagram не виконуються, тому затримка не потрібна
                if self.fast_mode:
                    continue

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
        media_id, media_code = t.get("media_id"), t.get("media_code")

        plan = []
        if "story" in kinds:
            plan.append("story")
        if "like" in kinds and random.random() < c.like_probability:
            plan.append("like")
        random.shuffle(plan)

        did_any = False
        for i, action in enumerate(plan):
            if i > 0:
                human_pause(3, 8, self.stop_event)
            if action == "story":
                if not self.limiter.wait_for_slot("story"):
                    continue
                try:
                    stories = self.api(self.cl.user_stories, uid)[: c.max_stories_per_user]
                    if not stories:
                        continue
                    self.api(self.cl.story_seen, stories)
                    self.db.log_action(self.username, "story", len(stories))
                    log.info("[Воркер] 👁 @%s: переглянуто %d сторіс", uname, len(stories))
                    did_any = True
                except (ApiError, PleaseWaitFewMinutes, FeedbackRequired) as e:
                    log.warning("[Воркер] Пропускаю сторіс @%s (%s)", uname, e)
                    continue
            else:
                if not media_id:
                    try:
                        latest = pick_latest(self.cl.user_medias(uid, amount=2, username=uname))
                        if latest:
                            media_id, media_code = str(latest.id), str(latest.code)
                    except (PleaseWaitFewMinutes, FeedbackRequired, ApiError) as e:
                        log.warning("[Воркер] Не вдалося отримати пост @%s (%s)", uname, e)
                        continue
                if not media_id:
                    continue
                if not self.limiter.wait_for_slot("like"):
                    continue
                try:
                    if self.api(self.cl.media_like, media_id):
                        self.db.log_action(self.username, "like")
                        log.info("[Воркер] ❤️ @%s: лайк на %s (%d/%d)",
                                 uname, media_code or media_id, self.limiter.used_today("like"), self.limiter.per_day("like"))
                        did_any = True
                except (ApiError, PleaseWaitFewMinutes, FeedbackRequired) as e:
                    log.warning("[Воркер] Не вдалося поставити лайк @%s (%s)", uname, e)
                    continue
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