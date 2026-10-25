from __future__ import annotations
import json, random, re, string, time
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


def _edge_count(obj) -> int:
    """Повертає count з {"count": N}; -1 якщо поля немає (= невідомо, а не 0)."""
    if isinstance(obj, dict) and obj.get("count") is not None:
        try:
            return int(obj["count"])
        except (TypeError, ValueError):
            return -1
    return -1


def _to_int(v) -> int:
    """None / сміття -> -1 (невідомо)."""
    try:
        return int(v) if v is not None else -1
    except (TypeError, ValueError):
        return -1


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
        self._feed_cache: dict[int, list[MediaItem]] = {}
        self._web_info_cooldown_until = 0.0
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

    # ── службові ────────────────────────────────────────────────────
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

    # ── допоміжні методи для постів та профілів ─────────────────────
    @staticmethod
    def shortcode_to_pk(code: str) -> int:
        alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        pk = 0
        for ch in code:
            pk = pk * 64 + alphabet.index(ch)
        return pk

    @classmethod
    def media_pk_from_url(cls, url: str) -> int:
        m = re.search(r"/(?:p|reel|tv)/([A-Za-z0-9_-]+)", url)
        if not m:
            raise ValueError("Невірне посилання на пост")
        return cls.shortcode_to_pk(m.group(1))

    def _fetch_embed_profile(self, uid: int, username: str, full_name: str = "", amount: int = 4) -> Optional[tuple[UserInfo, list[MediaItem]]]:
        """Публічне отримання профілю та постів через /{username}/embed/ (БЕЗ куків і X-IG-App-ID — 0 лімітів 429)."""
        uname = (username or "").strip().lstrip("@")
        if not uname or uname.startswith("id"):
            return None

        lo, hi = self.delay_range
        time.sleep(random.uniform(min(1.5, lo), min(3.5, hi)))

        try:
            resp = requests.get(
                f"{self.BASE}/{quote(uname)}/embed/",
                headers={
                    "User-Agent": self.DESKTOP_UA,
                    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language": "en-US,en;q=0.9",
                },
                proxies=self.session.proxies or None,
                timeout=20,
            )
        except requests.RequestException:
            return None

        if resp.status_code == 404:
            raise UserNotFound(f"404 Not Found (@{uname})")
        if resp.status_code != 200 or not resp.text:
            return None

        html = resp.text
        if "Page Not Found" in html or "Sorry, this page isn't available" in html:
            raise UserNotFound(f"Профіль @{uname} недоступний")

        is_private = "This Account is Private" in html or '"is_private":true' in html
        follower_count = -1
        captions: list[str] = []
        items: list[MediaItem] = []

        # Витягуємо дані з contextJSON, якщо він присутній в embed
        m_ctx = re.search(r'contextJSON"\s*,\s*"((?:\\.|[^"\\])*)"', html)
        if m_ctx:
            try:
                raw_json = json.loads(f'"{m_ctx.group(1)}"')
                ctx = json.loads(raw_json)
                owner = (ctx.get("context") or {}).get("graphql_media") or ctx.get("owner") or {}
            except Exception:
                owner = {}

        # Витягуємо всі shortcode постів зі сторінки embed
        codes: list[str] = []
        for m in re.finditer(r'\\?"shortcode\\?"\s*:\s*\\?"([A-Za-z0-9_-]{8,15})\\?"', html):
            c = m.group(1)
            if c not in codes:
                codes.append(c)
        if not codes:
            for m in re.finditer(r"/(?:p|reel)/([A-Za-z0-9_-]{8,15})/", html):
                c = m.group(1)
                if c not in codes:
                    codes.append(c)

        if not codes:
            log.warning("[IG] embed @%s: HTTP 200, %d байт, shortcode не знайдено", uname, len(html))

        now_dt = datetime.now(tz=timezone.utc)
        for code in codes[:amount]:
            try:
                pk = self.shortcode_to_pk(code)
                items.append(MediaItem(
                    id=str(pk),
                    pk=pk,
                    code=code,
                    taken_at=now_dt,
                ))
            except ValueError:
                continue

        # ВИПРАВЛЕНО: якщо embed не віддав жодного shortcode — кількість постів НЕВІДОМА (-1),
        # а не 0. Інакше всі анкети отримують skipped_numbers.
        media_count = len(codes) if codes else -1
        info = UserInfo(
            pk=int(uid),
            username=uname,
            full_name=full_name or "",
            biography=" ".join(captions)[:500],
            is_private=is_private,
            is_business=False,
            category="",
            media_count=media_count,
            follower_count=follower_count,
            following_count=-1,
        )
        return info, items

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

    def user_info(self, uid: int, username: str = "", full_name: str = "") -> UserInfo:
        uname = (username or "").strip().lstrip("@")

        # 1. Якщо відомий username — використовуємо веб-ендпоінт web_profile_info (повертає і анкету, і пости за 1 запит)
        if uname and not uname.startswith("id") and time.time() >= self._web_info_cooldown_until:
            try:
                data = self._req(
                    "GET",
                    f"{self.API}/users/web_profile_info/?username={quote(uname)}",
                    headers={"Referer": f"{self.BASE}/{uname}/"},
                    context="веб-профіль",
                    quiet_html=True,
                )
                u = (data.get("data") or {}).get("user")
                if not u:
                    log.warning("[IG] web_profile_info @%s: немає user у відповіді: %s", uname, str(data)[:200])
                if u:
                    media_edge = u.get("edge_owner_to_timeline_media") or {}
                    edges = media_edge.get("edges") or []
                    items: list[MediaItem] = []
                    for edge in edges[:6]:
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

                    # ВИПРАВЛЕНО: порожній список постів не кешуємо
                    if items:
                        self._feed_cache[int(uid)] = items

                    # ВИПРАВЛЕНО: відсутнє поле = -1 (невідомо), а не 0
                    media_count = _edge_count(media_edge)
                    if media_count < 0 and items:
                        media_count = len(items)

                    return UserInfo(
                        pk=int(u.get("id") or uid),
                        username=u.get("username") or uname,
                        full_name=u.get("full_name") or full_name or "",
                        biography=u.get("biography") or "",
                        is_private=bool(u.get("is_private", False)),
                        is_business=bool(u.get("is_business_account") or u.get("is_professional_account")),
                        category=u.get("category_name") or "",
                        media_count=media_count,
                        follower_count=_edge_count(u.get("edge_followed_by")),
                        following_count=_edge_count(u.get("edge_follow")),
                    )
            except (PleaseWaitFewMinutes, FeedbackRequired):
                raise  # акаунт під лімітом/блоком — іншими ендпоінтами не довбемо
            except ApiError as e:
                self._web_info_cooldown_until = time.time() + 900
                log.warning("[IG] web_profile_info @%s не вдалося: %s (кулдаун 15 хв)", uname, e)

        embed_info: Optional[UserInfo] = None

        # 2. Якщо web_profile_info на кулдауні — беремо через публічний embed
        if uname and not uname.startswith("id"):
            res = self._fetch_embed_profile(uid, uname, full_name=full_name, amount=6)
            if res is not None:
                embed_info, items = res
                # порожній список не кешуємо, щоб user_medias() спробував резервні ендпоінти
                if items:
                    self._feed_cache[int(uid)] = items
                # embed дав щось певне (приватний акаунт або відома кількість постів)
                if embed_info.is_private or embed_info.media_count >= 0:
                    return embed_info

        # 3. Авторизований /users/{uid}/info/ — коли попередні джерела нічого не дали
        #    (або username невідомий, наприклад при реєстрації акаунта)
        try:
            data = self._req("GET", f"{self.API}/users/{uid}/info/", context="завантаження профілю", quiet_html=True)
        except ApiError as e:
            log.warning("[IG] users/%s/info не вдалося: %s", uid, e)
            if embed_info is not None:
                return embed_info
            raise
        u = data.get("user")
        if not u:
            raise ApiError(f"user_info без user для ID {uid}: {str(data)[:200]}")
        return UserInfo(
            pk=int(u["pk"]),
            username=u.get("username", "") or uname,
            full_name=u.get("full_name", "") or full_name or "",
            biography=u.get("biography", "") or "",
            is_private=bool(u.get("is_private", False)),
            is_business=bool(u.get("is_business", False)),
            category=u.get("category", "") or "",
            media_count=_to_int(u.get("media_count")),
            follower_count=_to_int(u.get("follower_count")),
            following_count=_to_int(u.get("following_count")),
        )

    def user_medias(self, uid: int, amount: int = 4, username: str = "") -> list[MediaItem]:
        # 1. Якщо пости вже були завантажені під час user_info() — миттєво повертаємо з кешу (0 запитів до IG)
        if int(uid) in self._feed_cache:
            cached = self._feed_cache.pop(int(uid))
            if cached:
                return cached[:amount]

        uname = (username or "").strip().lstrip("@")
        known_name = bool(uname) and not uname.startswith("id")

        if known_name:
            # 3. Веб-ендпоінт за username. Успішна порожня відповідь = постів справді немає
            try:
                return self._feed_items(f"{self.API}/feed/user/{quote(uname)}/username/?count={amount}", amount)
            except ApiError as e:
                log.warning("[IG] feed/user/%s/username не вдалося: %s", uname, e)

        # 4. Резерв за числовим uid
        try:
            return self._feed_items(f"{self.API}/feed/user/{int(uid)}/?count={amount}", amount)
        except ApiError as e:
            log.warning("[IG] feed/user/%s не вдалося: %s", uid, e)
            # НЕ повертаємо [] — інакше анкету помилково запишуть як "без постів"
            raise ApiError(f"Не вдалося отримати пости @{uname or uid}: {e}")

    def _feed_items(self, url: str, amount: int) -> list[MediaItem]:
        data = self._req("GET", url, quiet_html=True)
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
        except (ApiError, PleaseWaitFewMinutes, FeedbackRequired):
            pass

        try:
            data = self._req("GET", f"{self.API}/feed/reels_media/?reel_ids={uid}", quiet_html=True)
        except (ApiError, PleaseWaitFewMinutes, FeedbackRequired):
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

    def media_like(self, media_id: str) -> bool:
        media_pk = str(media_id).split("_")[0]
        self._req("POST", f"{self.API}/web/likes/{media_pk}/like/", quiet_html=True)
        return True


MobileInstaClient = WebInstaClient