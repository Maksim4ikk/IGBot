"""Запуск і зупинка фонових потоків воркера та парсера."""
from __future__ import annotations
import threading
import traceback
from . import state
from .bots import MainWorker, ScraperBot
from .database import Database
from .errors import StopWork
from .settings import Settings
from .state import log
from .webbridge import request_cancel


def _reason(e: Exception) -> str:
    """StopWork — наше «зрозуміле» повідомлення; інше — показуємо тип помилки та друкуємо стек у logcat."""
    if isinstance(e, StopWork):
        return str(e)
    traceback.print_exc()
    return f"{type(e).__name__}: {e}"


def start_worker_thread() -> None:
    with state.TASK_LOCK:
        if state.WORKER_RUNNING:
            return
        state.STOP_WORKER.clear()
        state.WORKER_RUNNING = True

    def _target():
        db = None
        try:
            db = Database()
            MainWorker(Settings.load(), db).run_daemon()
        except Exception as e:
            log.error("[Воркер Стоп] %s", _reason(e))
        finally:
            try:
                if db is not None:
                    db.close()
            finally:
                with state.TASK_LOCK:
                    state.WORKER_RUNNING = False

    thread = threading.Thread(target=_target, name="igbot-worker", daemon=True)
    try:
        thread.start()
    except Exception:
        with state.TASK_LOCK:
            state.WORKER_RUNNING = False
        raise


def stop_worker_thread() -> None:
    log.info("Зупинка Воркера...")
    state.STOP_WORKER.set()
    request_cancel("worker")


def start_scraper_thread(source_type: str, target: str, amount: int = 0) -> None:
    """amount = 0 — взяти кількість зі вкладки «Ліміти» (scrape_amount)."""
    with state.TASK_LOCK:
        if state.SCRAPER_RUNNING:
            return
        state.STOP_SCRAPER.clear()
        state.SCRAPER_RUNNING = True
        state.SCRAPER_TASK = f"{source_type}: {target}"

    def _target():
        db = None
        try:
            log.info("[Парсер] Запуск завдання %s (%s)...", source_type, target)
            db = Database()
            ScraperBot(Settings.load(), db).scrape(source_type, target, int(amount or 0))
        except Exception as e:
            log.error("[Парсер Стоп] %s", _reason(e))
        finally:
            try:
                if db is not None:
                    db.close()
            finally:
                with state.TASK_LOCK:
                    state.SCRAPER_RUNNING = False
                    state.SCRAPER_TASK = ""

    thread = threading.Thread(target=_target, name="igbot-scraper", daemon=True)
    try:
        thread.start()
    except Exception:
        with state.TASK_LOCK:
            state.SCRAPER_RUNNING = False
            state.SCRAPER_TASK = ""
        raise


def stop_scraper_thread() -> None:
    log.info("Зупинка Парсера...")
    state.STOP_SCRAPER.set()
    request_cancel("scraper")
