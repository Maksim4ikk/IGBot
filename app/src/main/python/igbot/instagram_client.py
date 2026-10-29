"""HTTP-клієнт Instagram Web (requests). Лише те, що працює на практиці:
сесія/куки, список лайкерів, підписники, перегляд сторіс.

Усе, що Instagram блокує для запитів з Python (профілі, стрічки, лайк, хештеги),
робиться через WebView — див. webbridge.py.
"""
from __future__ import annotations
import json, random, re, string, time
from pathlib import Path
from typing import Optional
from urllib.parse import quote, unquote
import requests
from .errors import (
    ApiError, ChallengeRequired, FeedbackRequired, LoginRequired,
    PleaseWaitFewMinutes, StopWork, UserNotFound,
)
from .models import StoryItem, UserInfo, UserShort
from .state import log


class WebInstaClient:
    WEB_UA = (
        "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
    )
    DESKTOP_UA = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )
    APP_ID = "936619743392459"
    BASE = "https://www.instagram.com"
    API = "https://www.instagram.com/api/v1"
    DEFAULT_LANG = "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"

    def __init__(self, user_agent: str = "", accept_language: str = ""):
        self.session = requests.Session()
        self.delay_range = [2.5, 5.0]
        self.session.headers.update({
            "User-Agent": (user_agent or "").strip() or self.WEB_UA,
            "X-IG-App-ID": self.APP_ID,
            "X-ASBD-ID": "129477",
            "X-IG-WWW-Claim": "0",
            "X-Requested-With": "XMLHttpRequest",
            "Accept": "*/*",
            "Accept-Language": (accept_language or "").strip() or self.DEFAULT_LANG,
            "Origin": self.BASE,
            "Referer": self.BASE + "/",
        })

    def set_proxy(self, proxy_url: str) -> None:
        if proxy_url:
            self.session.proxies = {"http": proxy_url, "https": proxy_url}

    def _cookie(self, name: str) -> str:
        val = ""
        for c in self.session.cookies:
            if c.name == name and c.value:
                val = c.value
        return val

    def load_settings(self, path: Path) -> None:
        data = json.loads(path.read_text(encoding="utf-8"))
        for k, v in data.get("cookies", {}).items():
            if v:
                self.session.cookies.set(k, str(v), domain=".instagram.com")

    def dump_settings(self, path: Path) -> None:
        cookies = {c.name: c.value for c in self.session.cookies if c.value}
        path.write_text(json.dumps({"cookies": cookies}, indent=2), encoding="utf-8")

    def _bootstrap_csrf(self) -> None:
        """Безпечне оновлення csrftoken без втрати поточної сесії."""
        old_csrf = self._cookie("csrftoken")

        for cookie in list(self.session.cookies):
            if cookie.name == "csrftoken":
                try:
                    self.session.cookies.clear(
                        domain=cookie.domain,
                        path=cookie.path,
                        name=cookie.name,
                    )
                except KeyError:
                    pass

        try:
            r_shared = self.session.get(f"{self.API}/web/data/shared_data/", timeout=15)
            if not self._cookie("csrftoken"):
                m = re.search(r'\\?"csrf_token\\?"\s*:\s*\\?"([A-Za-z0-9_-]{16,64})\\?"', r_shared.text)
                if m:
                    self.session.cookies.set("csrftoken", m.group(1), domain=".instagram.com")
        except requests.RequestException:
            pass

        if not self._cookie("csrftoken"):
            try:
                r_home = self.session.get(
                    self.BASE + "/",
                    headers={"Accept": "text/html,application/xhtml+xml,*/*;q=0.8"},
                    timeout=20,
                    )
                if not self._cookie("csrftoken"):
                    m = re.search(r'\\?"csrf_token\\?"\s*:\s*\\?"([A-Za-z0-9_-]{16,64})\\?"', r_home.text)
                    if m:
                        self.session.cookies.set("csrftoken", m.group(1), domain=".instagram.com")
            except requests.RequestException as e:
                if not old_csrf:
                    raise LoginRequired(f"Не вдалося оновити csrftoken: {e}") from e

        if not self._cookie("csrftoken"):
            fallback_csrf = old_csrf or "".join(random.choices(string.ascii_letters + string.digits, k=32))
            self.session.cookies.set("csrftoken", fallback_csrf, domain=".instagram.com")

    def _req(self, method: str, url: str, delay: Optional[tuple] = None,
             context: str = "", quiet_html: bool = False, _csrf_retried: bool = False, **kwargs) -> dict:
        lo, hi = delay if delay else self.delay_range
        time.sleep(random.uniform(lo, hi))

        if not self._cookie("csrftoken"):
            self._bootstrap_csrf()

        headers = dict(kwargs.pop("headers", {}) or {})
        csrf = self._cookie("csrftoken")
        if csrf:
            headers["X-CSRFToken"] = csrf

        resp = self.session.request(method, url, headers=headers, timeout=25, **kwargs)

        if resp.status_code == 404:
            raise UserNotFound(f"404 Not Found: {method} {url.split('?')[0].removeprefix(self.BASE)[:90]}")
        if resp.status_code == 429:
            retry_after = resp.headers.get("Retry-After", "").strip()
            retry_seconds = int(retry_after) if retry_after.isdigit() else None
            endpoint = url.split("?", 1)[0].removeprefix(self.BASE)
            message = f"Забагато запитів (429): {method} {endpoint}"
            if context:
                message += f" ({context})"
            if retry_seconds is not None:
                message += f"; Retry-After {retry_seconds} с"
            raise PleaseWaitFewMinutes(message, retry_after=retry_seconds)
        if "/accounts/login" in resp.url:
            raise LoginRequired("Сесія вимагає повторного входу")
        if "/challenge" in resp.url:
            raise ChallengeRequired("Підтвердіть вхід в офіційному додатку Instagram!")

        try:
            data = resp.json()
        except Exception:
            if not quiet_html:
                log.warning("IG не-JSON: %s %s | %s", resp.status_code, resp.url[-60:], resp.text[:200].replace("\n", " "))
            if resp.status_code in (401, 403):
                raise LoginRequired(f"Відмова доступу ({resp.status_code})")
            if resp.status_code >= 400:
                raise PleaseWaitFewMinutes(f"HTTP помилка {resp.status_code}")
            raise ApiError("Не-JSON відповідь (HTTP 200)")

        if not isinstance(data, dict):
            raise ApiError(f"Несподіваний формат відповіді: {str(data)[:150]}")

        msg = str(data.get("message", "")).lower()
        if data.get("require_login") is True or "login_required" in msg or "user_has_logged_out" in msg:
            raise LoginRequired(msg or "require_login")
        if "csrf token" in msg and ("missing" in msg or "incorrect" in msg):
            if not _csrf_retried:
                self._bootstrap_csrf()
                return self._req(
                    method, url, delay=delay, context=context,
                    quiet_html=quiet_html, _csrf_retried=True, headers=headers, **kwargs
                )
            raise LoginRequired("Instagram відхилив csrftoken; оновіть sessionid у вкладці Акаунти")
        if "useragent mismatch" in msg:
            raise LoginRequired("useragent mismatch: вкажіть у web_user_agent той самий UA, що в браузері з sessionid")
        if "challenge_required" in msg or "checkpoint_required" in msg:
            raise ChallengeRequired("Підтвердіть вхід в офіційному додатку Instagram!")
        if "feedback_required" in msg or data.get("spam") is True:
            raise FeedbackRequired(data.get("feedback_message", "Action Block"))
        if "please wait" in msg or ("wait" in msg and resp.status_code == 400):
            raise PleaseWaitFewMinutes(msg)

        if data.get("status") == "fail":
            log.warning("IG fail (%s): %s", resp.status_code, str(data)[:300])
            raise ApiError(msg or f"status=fail ({resp.status_code})")
        return data

    # ── вхід ────────────────────────────────────────────────────────

    def login_by_sessionid(self, sessionid: str) -> None:
        clean_sid = unquote(sessionid.strip())
        if not clean_sid:
            raise LoginRequired("Порожній sessionid")
        self.session.cookies.set("sessionid", clean_sid, domain=".instagram.com")
        if ":" in clean_sid:
            self.session.cookies.set("ds_user_id", clean_sid.split(":")[0], domain=".instagram.com")
        self._bootstrap_csrf()

    def login(self, username: str, password: str) -> None:
        self._bootstrap_csrf()
        headers = {"Referer": self.BASE + "/accounts/login/", "X-CSRFToken": self._cookie("csrftoken")}
        enc_password = f"#PWD_INSTAGRAM_BROWSER:0:{int(time.time())}:{password}"
        resp = self.session.post(
            self.BASE + "/api/v1/web/accounts/login/ajax/",
            data={
                "username": username,
                "enc_password": enc_password,
                "queryParams": "{}",
                "optIntoOneTap": "false",
            },
            headers=headers,
            timeout=25,
            )
        try:
            data = resp.json()
        except Exception:
            raise StopWork(f"Помилка входу (HTTP {resp.status_code}). Вставте куку sessionid у вкладці Акаунти.")

        if not data.get("authenticated"):
            if data.get("two_factor_required"):
                raise StopWork("Увімкнено 2FA: вставте куку sessionid у вкладці Акаунти")
            if "checkpoint" in str(data).lower() or "challenge" in str(data).lower():
                raise ChallengeRequired("Підтвердіть вхід у додатку Instagram ('Це був я')")
            raise StopWork(f"Вхід відхилено ({data.get('message', 'перевірте пароль або вставте sessionid')})")

    def verify_session(self) -> dict:
        uid = self._cookie("ds_user_id")
        if not uid:
            raise LoginRequired("Немає ds_user_id — сесія невалідна")
        data = self._req("GET", f"{self.API}/users/{uid}/info/", context="перевірка сесії")
        if not data.get("user"):
            raise LoginRequired(f"Сесія невалідна: {str(data)[:150]}")
        return data

    # ── дані ────────────────────────────────────────────────────────

    def user_info(self, uid: int) -> UserInfo:
        """Профіль за числовим ID (авторизований ендпоінт; Instagram часто віддає на нього 429)."""
        data = self._req("GET", f"{self.API}/users/{uid}/info/", context="завантаження профілю", quiet_html=True)
        u = data.get("user")
        if not u:
            raise ApiError(f"user_info без user для ID {uid}: {str(data)[:200]}")
        return UserInfo(
            pk=int(u["pk"]),
            username=u.get("username", ""),
            full_name=u.get("full_name", "") or "",
            biography=u.get("biography", "") or "",
            is_private=bool(u.get("is_private", False)),
            is_business=bool(u.get("is_business", False)),
            category=u.get("category", "") or "",
            media_count=int(u.get("media_count", -1) if u.get("media_count") is not None else -1),
            follower_count=int(u.get("follower_count", -1) if u.get("follower_count") is not None else -1),
            following_count=int(u.get("following_count", -1) if u.get("following_count") is not None else -1),
        )

    def user_followers_v1_chunk(self, uid: int, max_amount: int = 100) -> tuple[list[UserShort], str]:
        results: list[UserShort] = []
        max_id = ""
        while len(results) < max_amount:
            count = min(50, max_amount - len(results))
            url = f"{self.API}/friendships/{uid}/followers/?count={count}"
            if max_id:
                url += f"&max_id={max_id}"
            data = self._req("GET", url)
            users = data.get("users") or []
            if not users:
                break
            for u in users:
                results.append(UserShort(
                    pk=int(u["pk"]),
                    username=u.get("username", ""),
                    full_name=u.get("full_name", "") or "",
                    is_private=u.get("is_private"),
                ))
            max_id = data.get("next_max_id") or ""
            if not max_id:
                break
        return results[:max_amount], max_id

    def media_likers(self, media_pk: int) -> list[UserShort]:
        data = self._req("GET", f"{self.API}/media/{media_pk}/likers/")
        return [
            UserShort(
                pk=int(u["pk"]),
                username=u.get("username", ""),
                full_name=u.get("full_name", "") or "",
                is_private=u.get("is_private"),
            )
            for u in data.get("users") or []
        ]

    def media_commenters(self, media_pk: int, max_amount: int = 100) -> list[UserShort]:
        """Автори коментарів верхнього рівня (без повторів), до max_amount людей.
        Коментарі приходять сторінками: у відповіді є курсор (next_min_id або next_max_id), який передаємо далі."""
        users: list[UserShort] = []
        seen: set[int] = set()
        cursor_param, cursor = "", ""
        for _ in range(300):  # запобіжник від нескінченного циклу
            query = "can_support_threading=true&permalink_enabled=false"
            if cursor:
                query += f"&{cursor_param}={quote(cursor, safe='')}"
            data = self._req("GET", f"{self.API}/media/{media_pk}/comments/?{query}",
                             context="коментарі", quiet_html=True)
            for c in data.get("comments") or []:
                u = c.get("user") or {}
                raw_pk = u.get("pk") or u.get("pk_id") or c.get("user_id")
                username = u.get("username", "")
                if not raw_pk or not username:
                    continue
                pk = int(raw_pk)
                if pk in seen:
                    continue
                seen.add(pk)
                users.append(UserShort(
                    pk=pk, username=username, full_name=u.get("full_name", "") or "",
                    is_private=u.get("is_private")))
                if len(users) >= max_amount:
                    return users
            nxt, param = data.get("next_min_id"), "min_id"
            if not nxt:
                nxt, param = data.get("next_max_id"), "max_id"
            if not nxt or data.get("has_more_comments") is False:
                break
            cursor_param = param
            cursor = nxt if isinstance(nxt, str) else json.dumps(nxt)
        return users

    # ── сторіс ──────────────────────────────────────────────────────

    def user_stories(self, uid: int) -> list[StoryItem]:
        try:
            variables = json.dumps(
                {
                    "reel_ids": [str(uid)],
                    "tag_names": [],
                    "location_ids": [],
                    "highlight_reel_ids": [],
                    "precomposed_overlay": False,
                    "show_story_viewer_list": False,
                },
                separators=(",", ":"),
            )
            gql = self._req(
                "GET",
                f"{self.BASE}/graphql/query/?query_hash=de8017ee0a7c9c45ec4260733d81ea31&variables={quote(variables)}",
                quiet_html=True,
            )
            if "data" in gql:
                stories: list[StoryItem] = []
                for r in ((gql.get("data") or {}).get("reels_media") or []):
                    for s in (r.get("items") or []):
                        if s.get("id"):
                            stories.append(StoryItem(
                                pk=int(s["id"]),
                                user_pk=int(uid),
                                taken_at=int(s.get("taken_at_timestamp", time.time())),
                            ))
                return stories
        except (ApiError, PleaseWaitFewMinutes, FeedbackRequired, UserNotFound) as e:
            log.info("[IG] сторіс graphql не вдалося: %s", e)

        try:
            data = self._req("GET", f"{self.API}/feed/reels_media/?reel_ids={uid}", quiet_html=True)
        except (ApiError, PleaseWaitFewMinutes, FeedbackRequired, UserNotFound) as e:
            log.info("[IG] сторіс reels_media не вдалося: %s", e)
            return []

        raw: list = []
        reels = data.get("reels")
        if isinstance(reels, dict):
            raw = (reels.get(str(uid)) or {}).get("items") or []
        if not raw:
            for r in data.get("reels_media") or []:
                owner = str((r.get("user") or {}).get("pk", r.get("id", "")))
                if owner == str(uid):
                    raw = r.get("items") or []
                    break
        stories: list[StoryItem] = []
        for s in raw:
            stories.append(StoryItem(
                pk=int(s["pk"]),
                user_pk=int(uid),
                taken_at=int(s.get("taken_at", time.time())),
            ))
        return stories

    def story_seen(self, stories: list[StoryItem]) -> bool:
        if not stories:
            return False
        now_ts = int(time.time())
        for st in stories:
            self._req(
                "POST", f"{self.API}/stories/reel/seen",
                delay=(0.6, 1.6),
                data={
                    "reelMediaId": str(st.pk),
                    "reelMediaOwnerId": str(st.user_pk),
                    "reelId": str(st.user_pk),
                    "reelMediaTakenAt": str(st.taken_at),
                    "viewSeenAt": str(now_ts),
                },
                quiet_html=True,
            )
        return True
