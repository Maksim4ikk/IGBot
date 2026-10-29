from __future__ import annotations
import sqlite3, time
from typing import Optional
from . import state
class Database:
    def __init__(self):
        db_path = str(state.APP_DIR / "igbot.db")
        self.conn = sqlite3.connect(db_path, timeout=30.0, isolation_level=None, check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA synchronous=NORMAL")
        self.conn.executescript("""
            CREATE TABLE IF NOT EXISTS targets (
                user_id INTEGER PRIMARY KEY,
                username TEXT,
                status TEXT NOT NULL,
                latest_media_id TEXT,
                latest_media_code TEXT,
                source TEXT,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_targets_status ON targets(status, updated_at);
            CREATE INDEX IF NOT EXISTS idx_targets_username ON targets(username COLLATE NOCASE);
            CREATE TABLE IF NOT EXISTS action_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account TEXT NOT NULL,
                kind TEXT NOT NULL,
                ts REAL NOT NULL,
                count INTEGER NOT NULL DEFAULT 1
            );
            CREATE INDEX IF NOT EXISTS idx_action ON action_log(account, kind, ts);
            CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
        """)
        self.conn.execute("DELETE FROM action_log WHERE ts < ?", (time.time() - 7 * 86400,))

    def exists(self, uid: int) -> bool:
        return self.conn.execute("SELECT 1 FROM targets WHERE user_id=?", (uid,)).fetchone() is not None

    def exists_username(self, username: str) -> bool:
        """Чи є людина з таким нікнеймом (без урахування регістру) — захист від дублів, коли ID різні."""
        if not username:
            return False
        return self.conn.execute(
            "SELECT 1 FROM targets WHERE username = ? COLLATE NOCASE LIMIT 1", (username,)).fetchone() is not None

    def add_target(self, uid: int, username: str, status: str, source: str = "",
                   media_id: Optional[str] = None, media_code: Optional[str] = None) -> bool:
        now = time.time()
        cur = self.conn.execute(
            """INSERT OR IGNORE INTO targets
               (user_id, username, status, latest_media_id, latest_media_code, source, created_at, updated_at)
               VALUES (?,?,?,?,?,?,?,?)""",
            (uid, username, status, media_id, media_code, source, now, now))
        return cur.rowcount == 1

    def claim_next(self) -> Optional[dict]:
        c = self.conn
        c.execute("BEGIN IMMEDIATE")
        try:
            row = c.execute(
                """SELECT user_id, username, latest_media_id, latest_media_code
                   FROM targets WHERE status='ready' ORDER BY updated_at ASC LIMIT 1""").fetchone()
            if row:
                c.execute("UPDATE targets SET status='processing', updated_at=? WHERE user_id=?",
                          (time.time(), row[0]))
            c.execute("COMMIT")
        except Exception:
            c.execute("ROLLBACK")
            raise
        if not row:
            return None
        return {"user_id": row[0], "username": row[1], "media_id": row[2], "media_code": row[3]}

    def complete(self, uid: int, status: str, error: Optional[str] = None) -> None:
        self.conn.execute("UPDATE targets SET status=?, last_error=?, updated_at=? WHERE user_id=?",
                          (status, error, time.time(), uid))

    def fail(self, uid: int, error: str, max_attempts: int) -> None:
        self.conn.execute(
            """UPDATE targets SET attempts=attempts+1, last_error=?, updated_at=?,
               status=CASE WHEN attempts+1>=? THEN 'error' ELSE 'ready' END WHERE user_id=?""",
            (error[:300], time.time(), max_attempts, uid))

    def release(self, uid: int) -> None:
        self.conn.execute("UPDATE targets SET status='ready' WHERE user_id=? AND status='processing'", (uid,))

    def release_stale(self, older_than_s: float) -> int:
        cur = self.conn.execute(
            "UPDATE targets SET status='ready' WHERE status='processing' AND updated_at < ?",
            (time.time() - older_than_s,))
        return cur.rowcount

    def retry_errors(self) -> int:
        cur = self.conn.execute("UPDATE targets SET status='ready', attempts=0 WHERE status='error'")
        return cur.rowcount

    def stats(self) -> dict:
        return dict(self.conn.execute("SELECT status, COUNT(*) FROM targets GROUP BY status").fetchall())

    # ── лічильники на дашборді ─────────────────────────────────────
    def _reset_ts(self, kind: str) -> float:
        try:
            return float(self.get_meta(f"counter_reset:{kind}") or "0")
        except (TypeError, ValueError):
            return 0.0

    def dashboard_counts(self) -> dict:
        """Числа для плиток «Огляду». «Оброблено» і «Відсіяно» рахуються від моменту останнього
        скидання (сама історія лишається в БД — вона захищає від повторних лайків)."""
        def one(sql: str, *args) -> int:
            return self.conn.execute(sql, args).fetchone()[0]
        return {
            "ready": one("SELECT COUNT(*) FROM targets WHERE status='ready'"),
            "interacted": one("SELECT COUNT(*) FROM targets WHERE status='interacted' AND updated_at >= ?",
                              self._reset_ts("interacted")),
            "skipped": one("SELECT COUNT(*) FROM targets WHERE status LIKE 'skipped%' AND updated_at >= ?",
                           self._reset_ts("skipped")),
            "errors": one("SELECT COUNT(*) FROM targets WHERE status IN ('error','gone')"),
        }

    def clear_counter(self, kind: str) -> int:
        """Очищення плитки. ready/errors — записи видаляються. interacted/skipped — лічильник
        скидається, а записи лишаються, щоб бот не лайкав і не перевіряв тих самих людей повторно.
        Повертає, скільки було очищено."""
        if kind == "ready":
            return self.conn.execute("DELETE FROM targets WHERE status='ready'").rowcount
        if kind == "errors":
            return self.conn.execute("DELETE FROM targets WHERE status IN ('error','gone')").rowcount
        if kind in ("interacted", "skipped"):
            n = self.dashboard_counts()[kind]
            self.set_meta(f"counter_reset:{kind}", str(time.time()))
            return n
        raise ValueError(f"Невідомий лічильник: {kind}")

    def log_action(self, account: str, kind: str, count: int = 1) -> None:
        self.conn.execute("INSERT INTO action_log(account, kind, ts, count) VALUES (?,?,?,?)",
                          (account, kind, time.time(), count))

    def used_since(self, account: str, kind: str, since: float) -> int:
        return self.conn.execute(
            "SELECT COALESCE(SUM(count),0) FROM action_log WHERE account=? AND kind=? AND ts>=?",
            (account, kind, since)).fetchone()[0]

    def oldest_since(self, account: str, kind: str, since: float) -> Optional[float]:
        return self.conn.execute(
            "SELECT MIN(ts) FROM action_log WHERE account=? AND kind=? AND ts>=?",
            (account, kind, since)).fetchone()[0]

    def last_action(self, account: str, kind: str) -> Optional[tuple]:
        return self.conn.execute(
            "SELECT ts, count FROM action_log WHERE account=? AND kind=? ORDER BY ts DESC LIMIT 1",
            (account, kind)).fetchone()

    def get_meta(self, key: str) -> Optional[str]:
        row = self.conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row[0] if row else None

    def set_meta(self, key: str, value: str) -> None:
        self.conn.execute("INSERT OR REPLACE INTO meta(key, value) VALUES (?,?)", (key, value))

    def clear_blocks(self) -> int:
        return self.conn.execute(
            "DELETE FROM meta WHERE key LIKE 'blocked_until:%' "
            "OR key LIKE 'rate_limit_until:%' OR key LIKE 'rate_limit_streak:%'"
        ).rowcount

