from __future__ import annotations
import threading
from . import state
from .bots import MainWorker, ScraperBot
from .database import Database
from .settings import Settings
from .state import log
def start_worker_thread() -> None:
    if state.WORKER_RUNNING:
        return
    state.STOP_WORKER.clear()
    state.WORKER_RUNNING = True

    def _target():
        try:
            cfg = Settings.load()
            db = Database()
            MainWorker(cfg, db).run_daemon()
        except Exception as e:
            log.error("[Воркер Стоп] %s", e)
        finally:
            state.WORKER_RUNNING = False

    threading.Thread(target=_target, daemon=True).start()


def stop_worker_thread() -> None:
    log.info("Зупинка Воркера...")
    state.STOP_WORKER.set()


def start_scraper_thread(source_type: str, target: str, amount: int) -> None:
    if state.SCRAPER_RUNNING:
        return
    state.STOP_SCRAPER.clear()
    state.SCRAPER_RUNNING = True
    state.SCRAPER_TASK = f"{source_type}: {target}"

    def _target():
        try:
            log.info("[Парсер] Запуск завдання %s (%s)...", source_type, target)
            cfg = Settings.load()
            db = Database()
            ScraperBot(cfg, db).scrape(source_type, target, int(amount))
        except Exception as e:
            log.error("[Парсер Стоп] %s", e)
        finally:
            state.SCRAPER_RUNNING = False
            state.SCRAPER_TASK = ""

    threading.Thread(target=_target, daemon=True).start()


def stop_scraper_thread() -> None:
    log.info("Зупинка Парсера...")
    state.STOP_SCRAPER.set()

