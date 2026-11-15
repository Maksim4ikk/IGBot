"""Основний виконавець: бере цілі з черги, переглядає сторіс і лайкає пост (через WebView)."""
from __future__ import annotations
import random
from .base_bot import BaseBot
from .database import Database
from .errors import ApiError, MediaNotFound, StopWork, UserNotFound
from .rate_limiter import RateLimiter
from .settings import Settings
from .state import log, STOP_WORKER
from .utils import fmt_secs, freshest_code, human_pause, nap, pk_to_shortcode, shortcode_to_pk


class MainWorker(BaseBot):
    web_task_id = "worker"

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
                    res = self.web.latest_posts(uname)
                    code = freshest_code(res.list("codes")) if res.ok else None
                    if not code:
                        log.info("[Воркер] @%s: поста не знайдено (%s) — пропускаю лайк", uname, res.status)
                        continue
                    media_id, media_code = str(shortcode_to_pk(code)), code
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
        """Лайк через WebView. Повертає: liked | already_liked | skipped."""
        code = (media_code or "").strip()
        if not code:
            try:
                code = pk_to_shortcode(int(str(media_id).split("_")[0]))
            except (TypeError, ValueError):
                log.warning("[Воркер] Не вдалося визначити посилання з media_id=%s", media_id)
                return "skipped"
        res = self.web.like(code)
        if res.status == "liked":
            return "liked"
        if res.status == "already_liked":
            return "already_liked"
        log.warning("[Воркер] Лайк не підтверджено: %s (%s)", res.status, res.detail)
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
