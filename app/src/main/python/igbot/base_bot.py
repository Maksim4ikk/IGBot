"""Спільна основа ботів: сесія, вхід, обробка лімітів/блоків Instagram (api())."""
from __future__ import annotations
import re
import threading, time
from typing import Callable, Optional
from urllib.parse import unquote
import requests
from . import state
from .database import Database
from .errors import (
    ChallengeRequired, FeedbackRequired, LoginRequired, PleaseWaitFewMinutes, StopWork,
)
from .instagram_client import WebInstaClient
from .settings import Settings
from .state import log
from .utils import fmt_secs, nap
from .webbridge import WebBridge


class BaseBot:
    delay_override: Optional[list] = None
    verify_session_on_start = False
    web_task_id = "worker"

    def __init__(self, cfg: Settings, db: Database, username: str, password: str,
                 stop_event: threading.Event, proxy: str = "", sessionid: str = ""):
        self.cfg, self.db = cfg, db
        self.username, self.password = username.strip(), password.strip()
        self.proxy = proxy.strip()
        self.sessionid = sessionid.strip()
        self.stop_event = stop_event
        if not self.username:
            raise StopWork("Вкажіть логін акаунта у вкладці 'Акаунти'!")
        if not re.fullmatch(r"[A-Za-z0-9._]{1,30}", self.username):
            raise StopWork("Некоректний нікнейм акаунта. Перепідключіть акаунт у вкладці «Акаунти».")
        self.session_path = state.APP_DIR / "sessions" / f"{self.username}.json"
        try:
            self.throttle_streak = int(db.get_meta(f"rate_limit_streak:{self.username}") or "0")
        except (TypeError, ValueError):
            self.throttle_streak = 0
        self._check_blocked()
        self.cl = self._new_client()
        self._login()

    @property
    def web(self) -> WebBridge:
        """Дії через WebView від імені цього акаунта (куки беруться з поточної сесії)."""
        return WebBridge(self.cl.session.cookies, self.cfg.web_user_agent, self.web_task_id)

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
