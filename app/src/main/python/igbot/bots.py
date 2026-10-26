from __future__ import annotations
import hashlib, json, logging, random, re, threading, time
from datetime import datetime, timezone
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


# Зрозумілі підписи статусів для журналу (в БД лишаються технічні назви)
STATUS_TEXT = {
    "ready": "готово (є пост)",
    "skipped_private": "приватний акаунт — пропущено",
    "skipped_dead": "немає постів або недоступний — пропущено",
    "skipped_commercial": "магазин/бізнес — пропущено",
    "skipped_numbers": "не підходить за цифрами — пропущено",
}


class BaseBot:
    delay_override: Optional[list] = None
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
                if self.sessionid:
                    clean_sid = unquote(self.sessionid)
                    if not saved_sid or saved_sid != clean_sid:
                        self.cl.session.cookies.set("sessionid", clean_sid, domain=".instagram.com")
                        if ":" in clean_sid:
                            self.cl.session.cookies.set("ds_user_id", clean_sid.split(":")[0], domain=".instagram.com")
                if not self.cl._cookie("csrftoken"):
                    self.cl._bootstrap_csrf()
                    self.save_session()
                if self.verify_session_on_start:
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
    delay_override = [3.0, 6.0]
    # USE_WEBVIEW = True: останній пост кожного відкритого профілю береться зі сторінки профілю у WebView
    # (як це робить людина), бо API-запити профілів Instagram блокує (429 / spam).
    USE_WEBVIEW = True
    # LITE_MODE = True: профілі взагалі не перевіряються (0 запитів), пост шукатиме воркер.
    LITE_MODE = False

    def _evaluate_lite(self, uid: int, uname: str, full_name: str = ""):
        return uname, "ready", self.preset_medias.get(uid)

    def _web_latest_post(self, username: str) -> dict:
        """Сторінка профілю у WebView -> {"status", "codes", "detail"}."""
        from java import jclass  # лише Chaquopy (Android)
        cookies = {c.name: c.value for c in self.cl.session.cookies if c.value}
        raw = jclass("com.maks.igbot.data.WebLiker").latestPost(
            username, self.cfg.web_user_agent or "", json.dumps(cookies))
        return json.loads(str(raw))

    def _evaluate_web(self, uid: int, uname: str, full_name: str = ""):
        preset = self.preset_medias.get(uid)
        if preset is not None:
            return uname, "ready", preset
        try:
            res = self._web_latest_post(uname)
        except ImportError:
            raise StopWork("Режим WebView працює лише в Android-застосунку")
        status = res.get("status")

        if status == "ok":
            best = None  # найсвіжіший = найбільший pk (закріплені старі пости не заважають)
            for code in str(res.get("codes", "")).split(","):
                code = code.strip()
                if not code:
                    continue
                try:
                    pk = WebInstaClient.shortcode_to_pk(code)
                except ValueError:
                    continue
                if best is None or pk > best[0]:
                    best = (pk, code)
            if best is None:
                raise ApiError(f"@{uname}: не вдалося розібрати посилання на пости")
            pk, code = best
            ts = ((pk >> 23) + 1314220021721) / 1000  # час публікації закодований у pk
            media = MediaItem(id=str(pk), pk=pk, code=code,
                              taken_at=datetime.fromtimestamp(ts, tz=timezone.utc))
            return uname, "ready", media
        if status == "private":
            return uname, "skipped_private", None
        if status in ("no_posts", "not_found"):
            return uname, "skipped_dead", None
        if status == "login":
            raise StopWork("WebView не авторизований в Instagram — увійдіть у технічний акаунт у вкладці Акаунти")
        # unknown / timeout / error — НЕ записуємо в БД як "мертву" анкету
        raise ApiError(f"@{uname}: профіль не прочитано ({status}: {res.get('detail', '')})")

    def __init__(self, cfg: Settings, db: Database):
        # Блоки/паузи (429, Action Block) тут НЕ скидаємо: зняти їх можна вручну через unlock_all()

        super().__init__(
            cfg, db,
            username=cfg.scraper_username,
            password=cfg.scraper_password,
            stop_event=STOP_SCRAPER,
            proxy=cfg.scraper_proxy,
            sessionid=cfg.scraper_sessionid,
        )
        self.stop_re = build_stop_regex(self.cfg.stop_words)
        self.preset_medias: dict[int, MediaItem] = {}

    def _web_call(self, fn: str, *args) -> dict:
        """Виклик функції Kotlin-класу WebLiker (сторінки Instagram у WebView). Лише Android."""
        from java import jclass
        cookies = {c.name: c.value for c in self.cl.session.cookies if c.value}
        raw = getattr(jclass("com.maks.igbot.data.WebLiker"), fn)(
            *args, self.cfg.web_user_agent or "", json.dumps(cookies))
        return json.loads(str(raw))

    def _resolve_user_id(self, uname: str) -> int:
        """Числовий ID акаунта: читаємо зі сторінки профілю у WebView
        (старий пошук /web/search/topsearch/ повертає 404)."""
        try:
            res = self._web_call("profileId", uname)
        except ImportError:
            return self.api(self.cl.user_id_from_username, uname)  # поза Android
        st, detail = res.get("status"), res.get("detail", "")
        if st == "ok":
            return int(res["id"])
        if st == "not_found":
            raise StopWork(f"Акаунт @{uname} не знайдено")
        if st == "login":
            raise StopWork("WebView не авторизований в Instagram — увійдіть у технічний акаунт у вкладці Акаунти")
        raise StopWork(f"Не вдалося визначити ID @{uname}: {st} ({detail})")

    def _scrape_hashtag(self, tag: str, amount: int) -> None:
        """Хештег: відкриваємо сторінку тегу у WebView, беремо пости, потім відкриваємо кожен пост
        і дізнаємось автора. Лайкатимемо саме цей пост (він уже свіжий). Приватних тут не буває."""
        log.info("[Парсер] Відкриваю хештег #%s у WebView...", tag)
        try:
            res = self._web_call("hashtagPosts", tag, int(amount))
        except ImportError:
            raise StopWork("Збір за хештегом працює лише в Android-застосунку")
        st = res.get("status")
        if st == "login":
            raise StopWork("WebView не авторизований в Instagram — увійдіть у технічний акаунт у вкладці Акаунти")
        codes = [c.strip() for c in str(res.get("codes", "")).split(",") if c.strip()][: int(amount)]
        if not codes:
            raise StopWork(f"Хештег #{tag}: постів не знайдено ({st}: {res.get('detail', '')})")
        log.info("[Парсер] Знайдено постів за #%s: %d. Визначаю авторів (~10 с на пост)...", tag, len(codes))

        cands: list[UserShort] = []
        seen_names: set[str] = set()
        fails = 0
        for i, code in enumerate(codes, 1):
            if self.stop_event.is_set():
                break
            r = self._web_call("postAuthor", code)
            st = r.get("status")
            if st == "login":
                raise StopWork("WebView не авторизований в Instagram — увійдіть у технічний акаунт у вкладці Акаунти")
            uname = str(r.get("username", "")).strip()
            if st != "ok" or not uname:
                fails += 1
                log.warning("[Парсер] [%d/%d] пост %s: автора не визначено (%s: %s)",
                            i, len(codes), code, st, r.get("detail", ""))
                if fails >= 5:
                    raise StopWork("Не вдалося визначити авторів 5 постів поспіль — зупиняю парсер.")
                human_pause(self.cfg.scrape_delay_min, self.cfg.scrape_delay_max, self.stop_event)
                continue
            fails = 0
            if uname.lower() in seen_names:
                continue
            seen_names.add(uname.lower())

            try:
                mpk = WebInstaClient.shortcode_to_pk(code)
            except ValueError:
                continue
            uid = None
            for s in str(r.get("ids", "")).split(","):
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

    def scrape(self, source_type: str, target: str, amount: int = 100) -> None:
        target = target.strip()
        if source_type == "followers":
            uname = target.lstrip("@")
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
        elif source_type == "likers":
            pk = self.cl.media_pk_from_url(target)
            log.info("[Парсер] Завантажую лайкерів поста...")
            likers = self.api(self.cl.media_likers, pk)
            self._process(likers[:amount], f"likers:{pk}")
        elif source_type == "hashtag":
            self._scrape_hashtag(target.lstrip("#"), amount)

    def _evaluate(self, uid: int, uname: str, full_name: str = ""):
        c = self.cfg
        info = self.cl.user_info(uid, username=uname, full_name=full_name)
        name = info.username or uname

        # Діагностика: одразу видно, які цифри бачить фільтр (-1 = невідомо)
        log.info("[Парсер] @%s: posts=%s followers=%s following=%s private=%s",
                 name, info.media_count, info.follower_count, info.following_count, info.is_private)

        if info.is_private:
            return name, "skipped_private", None

        # ВИПРАВЛЕНО: -1 означає "невідомо", а не "0 постів" — такі анкети не відсікаємо
        if 0 <= info.media_count < max(1, c.min_posts):
            return name, "skipped_numbers", None

        # Перевіряємо підписників/підписки, якщо вони відомі (>= 0)
        if info.follower_count >= 0:
            if (info.following_count > c.max_following
                    or info.follower_count > c.max_followers
                    or info.follower_count < c.min_followers):
                return name, "skipped_numbers", None
        if c.skip_business and info.is_business:
            return name, "skipped_commercial", None
        if self.stop_re:
            text = " ".join(filter(None, [info.full_name, info.biography, info.category]))
            if self.stop_re.search(text):
                return name, "skipped_commercial", None

        preset_media = self.preset_medias.get(uid)
        if preset_media is not None:
            return name, "ready", preset_media

        # Отримуємо останній пост (без нього анкета НІКОЛИ не додається в ready!)
        latest = pick_latest(self.cl.user_medias(uid, amount=4, username=name))
        if latest is None:
            return name, "skipped_dead", None
        return name, "ready", latest

    def _process(self, cands: list[UserShort], source: str) -> None:
        new = [u for u in cands if not self.db.exists(u.pk)]
        log.info("[Парсер] Отримано %d анкет (нових: %d)", len(cands), len(new))
        if self.USE_WEBVIEW:
            log.info("[Парсер] Режим WebView: відкриваю профілі й беру останній пост")
        elif self.LITE_MODE:
            log.info("[Парсер] Швидкий режим: профілі не перевіряються, пост шукатиме воркер")
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
                    log.info("[Парсер] [%d/%d] @%s -> %s", i, len(new), uname, STATUS_TEXT["skipped_private"])
                    continue

                # 2. Миттєве відсіювання магазинів за іменем (0 запитів до API)
                if self.stop_re and self.stop_re.search(f"{uname} {u.full_name}"):
                    self.db.add_target(uid, uname, "skipped_commercial", source)
                    counter["skipped_commercial"] += 1
                    log.info("[Парсер] [%d/%d] @%s -> %s (за назвою)", i, len(new), uname, STATUS_TEXT["skipped_commercial"])
                    continue

                try:
                    if self.USE_WEBVIEW:
                        name, status, media = self._evaluate_web(uid, uname, u.full_name)
                    elif self.LITE_MODE:
                        name, status, media = self._evaluate_lite(uid, uname, u.full_name)
                    else:
                        name, status, media = self.api(self._evaluate, uid, uname, u.full_name)
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

                if self.LITE_MODE:
                    continue  # запитів не було — паузи й перерви не потрібні
                if self.preset_medias.get(uid) is not None:
                    continue  # пост відомий заздалегідь (хештег) — запиту не було, пауза не потрібна

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
                "[Парсер] Завершено! ready: %d | приватних: %d | комерції: %d | за цифрами/без постів: %d | помилок: %d",
                counter["ready"], counter["skipped_private"],
                counter["skipped_commercial"],
                counter["skipped_numbers"] + counter["skipped_dead"],
                counter["error"],
                )


class MainWorker(BaseBot):
    def __init__(self, cfg: Settings, db: Database):
        uname = cfg.main_username.strip()
        if uname:
            db.set_meta(f"blocked_until:{uname}", "0")
            db.set_meta(f"rate_limit_until:{uname}", "0")
            db.set_meta(f"rate_limit_streak:{uname}", "0")

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
            log.warning("[Воркер] @%s: 404/не знайдено (%s) — позначено gone", t["username"], e)
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
                except (ApiError, UserNotFound, MediaNotFound) as e:
                    log.warning("[Воркер] Пропускаю сторіс @%s (%s)", uname, e)
                    continue
            else:
                if not media_id:
                    try:
                        medias = self.api(self.cl.user_medias, uid, amount=2, username=uname)
                    except ApiError as e:
                        log.info("[Воркер] @%s: не вдалося отримати пости (%s) — пропускаю лайк", uname, e)
                        continue
                    latest = pick_latest(medias)
                    if latest:
                        media_id, media_code = str(latest.id), str(latest.code)
                    else:
                        log.info("[Воркер] @%s не має постів — пропускаю", uname)
                        continue
                if not self.limiter.wait_for_slot("like"):
                    continue
                try:
                    outcome = self._like_post(media_id, media_code)
                    if outcome == "liked":
                        self.db.log_action(self.username, "like")
                        log.info("[Воркер] ❤️ @%s: лайк на %s (%d/%d)",
                                 uname, media_code or media_id, self.limiter.used_today("like"), self.limiter.per_day("like"))
                        did_any = True
                    elif outcome == "already_liked":
                        log.info("[Воркер] @%s: пост уже лайкнутий — пропускаю", uname)
                except (ApiError, UserNotFound, MediaNotFound) as e:
                    log.warning("[Воркер] Не вдалося поставити лайк @%s (%s)", uname, e)
                    continue
        return did_any

    def _like_post(self, media_id: str, media_code: str = "") -> str:
        """Лайк через WebView (REST /web/likes/.../like/ більше не існує — 404).
        Повертає: liked | already_liked | skipped."""
        code = (media_code or "").strip()
        if not code:
            try:
                code = WebInstaClient.pk_to_shortcode(int(str(media_id).split("_")[0]))
            except (TypeError, ValueError):
                log.warning("[Воркер] Не вдалося визначити посилання з media_id=%s", media_id)
                return "skipped"
        url = f"https://www.instagram.com/p/{code}/"
        try:
            from java import jclass  # доступно лише в Chaquopy (Android)
        except ImportError:
            return "liked" if self.api(self.cl.media_like, media_id) else "skipped"

        cookies = {c.name: c.value for c in self.cl.session.cookies if c.value}
        raw = jclass("com.maks.igbot.data.WebLiker").like(url, self.cfg.web_user_agent or "", json.dumps(cookies))
        res = json.loads(str(raw))
        status, detail = res.get("status"), res.get("detail", "")
        if status == "liked":
            return "liked"
        if status == "already_liked":
            return "already_liked"
        if status == "login_required":
            raise StopWork("WebView не авторизований в Instagram — увійдіть в основний акаунт у вкладці Акаунти")
        log.warning("[Воркер] Лайк через WebView не підтверджено: %s (%s)", status, detail)
        return "skipped"

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
