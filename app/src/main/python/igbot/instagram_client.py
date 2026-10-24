from __future__ import annotations
import json, random, re, time
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional
from urllib.parse import quote, unquote
import requests
from .errors import (
    ApiError, ChallengeRequired, FeedbackRequired, LoginRequired,
    MediaNotFound, PleaseWaitFewMinutes, StopWork, UserNotFound,
)
from .models import MediaItem, StoryItem, UserInfo, UserShort
from .state import log


class WebInstaClient:
    WEB_UA = (
        "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
    )
    APP_ID = "936619743392459"
    BASE = "https://www.instagram.com"
    API = "https://www.instagram.com/api/v1"
    DEFAULT_LANG = "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"

    def __init__(self, user_agent: str = "", accept_language: str = ""):
        self.session = requests.Session()
        self.delay_range = [2.5, 5.0]
        # Прибираємо маркер Android WebView (; wv), через який IG часто віддає HTML замість JSON
        clean_ua = re.sub(r";\s*wv\b", "", (user_agent or "").strip())
        clean_ua = re.sub(r"\bVersion/\d+\.\d+\s*", "", clean_ua).strip() or self.WEB_UA
        self.session.headers.update({
            "User-Agent": clean_ua,
            "X-IG-App-ID": self.APP_ID,
            "X-ASBD-ID": "129477",
            "X-IG-WWW-Claim": "0",
            "X-Requested-With": "XMLHttpRequest",
            "Accept": "*/*",
            "Accept-Language": (accept_language or "").strip() or self.DEFAULT_LANG,
            "Origin": self.BASE,
            "Referer": self.BASE + "/",
            "Sec-Fetch-Dest": "empty",
            "Sec-Fetch-Mode": "cors",
            "Sec-Fetch-Site": "same-origin",
        })

    # ── службові ────────────────────────────────────────────────────
    def set_proxy(self, proxy_url: str) -> None:
        if proxy_url:
            self.session.proxies = {"http": proxy_url, "https": proxy_url}

    def _cookie(self, name: str) -> str:
        val = ""
        for c in self.session.cookies:
            if c.name == name:
                val = c.value
        return val

    def load_settings(self, path: Path) -> None:
        data = json.loads(path.read_text(encoding="utf-8"))
        for k, v in data.get("cookies", {}).items():
            self.session.cookies.set(k, v, domain=".instagram.com")

    def dump_settings(self, path: Path) -> None:
        cookies = {c.name: c.value for c in self.session.cookies}
        path.write_text(json.dumps({"cookies": cookies}, indent=2), encoding="utf-8")

    def _bootstrap_csrf(self) -> None:
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
            self.session.get(
                self.BASE + "/",
                headers={"Accept": "text/html,application/xhtml+xml,*/*;q=0.8"},
                timeout=20,
                )
        except requests.RequestException as e:
            raise LoginRequired(f"Не вдалося оновити csrftoken: {e}") from e

        if not self._cookie("csrftoken"):
            raise LoginRequired(
                "Instagram не видав csrftoken. Перевірте sessionid, мережу або проксі."
            )

    def _req(self, method: str, url: str, delay: Optional[tuple] = None,
             context: str = "", **kwargs) -> dict:
        lo, hi = delay if delay else self.delay_range
        time.sleep(random.uniform(lo, hi))

        if not self._cookie("csrftoken"):
            try:
                self._bootstrap_csrf()
            except Exception:
                pass

        headers = kwargs.pop("headers", {})
        csrf = self._cookie("csrftoken")
        if csrf:
            headers["X-CSRFToken"] = csrf

        resp = self.session.request(method, url, headers=headers, timeout=25, **kwargs)

        if resp.status_code == 404:
            raise UserNotFound(f"404 Not Found ({url.split('?')[0][-40:]})")
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
            body_low = resp.text.lower()
            if "challenge_required" in body_low or "checkpoint_required" in body_low or "/challenge/" in body_low:
                raise ChallengeRequired("Підтвердіть вхід в офіційному додатку Instagram!")
            if '"is_logged_in":false' in body_low or "loginandsignuppage" in body_low:
                raise LoginRequired("Сесія не авторизована (оновіть sessionid)")
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
            raise LoginRequired("Instagram відхилив csrftoken; оновлюю сесію")
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

    # ── пошук / дані ────────────────────────────────────────────────
    def user_id_from_username(self, username: str) -> int:
        uname = username.strip().lstrip("@").lower()
        if not uname:
            raise UserNotFound("Вкажіть нікнейм профілю для пошуку")

        data = self._req(
            "GET",
            f"{self.API}/web/search/topsearch/?context=user&include_reel=false&query={quote(uname)}",
            context="пошук профілю",
        )
        for row in data.get("users") or []:
            user = row.get("user") or row
            found_name = str(user.get("username", "")).strip().lower()
            user_id = user.get("pk") or user.get("id")
            if found_name == uname and user_id:
                return int(user_id)

        raise UserNotFound(
            f"Instagram не повернув точний збіг для @{uname} у пошуку. "
            "Перевірте нікнейм і спробуйте пізніше."
        )

    def user_info(self, uid: int) -> UserInfo:
        data = self._req("GET", f"{self.API}/users/{uid}/info/", context="завантаження профілю")
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
            media_count=int(u.get("media_count", 0) or 0),
            follower_count=int(u.get("follower_count", 0) or 0),
            following_count=int(u.get("following_count", 0) or 0),
        )

    def user_medias(self, uid: int, amount: int = 4, username: str = "") -> list[MediaItem]:
        data = None
        uname = (username or "").strip().lstrip("@")
        # 1. Спочатку пробуємо веб-ендпоінт стрічки за username (менше лімітів і не віддає HTML)
        if uname and not uname.startswith("id"):
            try:
                data = self._req("GET", f"{self.API}/feed/user/{quote(uname)}/username/?count={amount}")
            except (ApiError, PleaseWaitFewMinutes):
                data = None

        # 2. Якщо за username не вийшло — пробуємо за числовим uid
        if data is None:
            try:
                data = self._req("GET", f"{self.API}/feed/user/{uid}/?count={amount}")
            except ApiError:
                # 3. Резервний запит через GraphQL
                variables = json.dumps({"id": str(uid), "first": amount}, separators=(",", ":"))
                gql = self._req(
                    "GET",
                    f"{self.BASE}/graphql/query/?query_hash=e769aa130647d2354c40ea6a439bfc08&variables={quote(variables)}",
                )
                edges = (((gql.get("data") or {}).get("user") or {}).get("edge_owner_to_timeline_media") or {}).get("edges") or []
                items: list[MediaItem] = []
                for edge in edges[:amount]:
                    node = edge.get("node") or {}
                    if not node.get("id"):
                        continue
                    ts = int(node.get("taken_at_timestamp", time.time()))
                    items.append(MediaItem(
                        id=str(node["id"]),
                        pk=int(node["id"]),
                        code=str(node.get("shortcode", "")),
                        taken_at=datetime.fromtimestamp(ts, tz=timezone.utc),
                    ))
                return items

        items: list[MediaItem] = []
        for m in (data.get("items") or [])[:amount]:
            ts = int(m.get("taken_at", time.time()))
            items.append(MediaItem(
                id=str(m.get("id", m.get("pk"))),
                pk=int(m.get("pk", 0)),
                code=str(m.get("code", "")),
                taken_at=datetime.fromtimestamp(ts, tz=timezone.utc),
            ))
        return items

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

    @staticmethod
    def media_pk_from_url(url: str) -> int:
        m = re.search(r"/(?:p|reel|tv)/([A-Za-z0-9_-]+)", url)
        if not m:
            raise ValueError("Невірне посилання на пост")
        code = m.group(1)
        alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        pk = 0
        for ch in code:
            pk = pk * 64 + alphabet.index(ch)
        return pk

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

    def hashtag_medias_recent(self, tag: str, amount: int = 100) -> list[MediaItem]:
        data = self._req("GET", f"{self.API}/tags/web_info/?tag_name={quote(tag)}")
        d = data.get("data") or data
        sections = list((d.get("recent") or {}).get("sections") or [])
        sections += list((d.get("top") or {}).get("sections") or [])
        if not sections:
            sections = data.get("sections") or []
        if not sections:
            log.warning("Хештег #%s: порожні секції. Відповідь: %s", tag, str(data)[:200])

        items: list[MediaItem] = []
        for sec in sections:
            for row in (sec.get("layout_content") or {}).get("medias", []):
                m = row.get("media")
                if m and m.get("user"):
                    u = m["user"]
                    ts = int(m.get("taken_at", time.time()))
                    items.append(MediaItem(
                        id=str(m.get("id", m.get("pk"))),
                        pk=int(m.get("pk", 0)),
                        code=str(m.get("code", "")),
                        taken_at=datetime.fromtimestamp(ts, tz=timezone.utc),
                        user=UserShort(
                            pk=int(u["pk"]),
                            username=u.get("username", ""),
                            full_name=u.get("full_name", "") or "",
                            is_private=u.get("is_private"),
                        ),
                    ))
                    if len(items) >= amount:
                        return items
        return items

    # ── сторіс / лайки ──────────────────────────────────────────────
    def user_stories(self, uid: int) -> list[StoryItem]:
        data = None
        # 1. Пробуємо POST /api/v1/feed/reels_media/ (не перехоплюється HTML-роутером мобільної версії)
        try:
            data = self._req("POST", f"{self.API}/feed/reels_media/", data={"reel_ids": str(uid)})
        except ApiError:
            pass

        # 2. Резервний GET-запит через GraphQL
        if data is None:
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
            )
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
            )
        return True

    def media_like(self, media_id: str) -> bool:
        media_pk = str(media_id).split("_")[0]
        self._req("POST", f"{self.API}/web/likes/{media_pk}/like/")
        return True


MobileInstaClient = WebInstaClient