from __future__ import annotations

import logging
import threading
from collections import deque
from pathlib import Path

STOP_WORKER = threading.Event()
STOP_SCRAPER = threading.Event()
WORKER_RUNNING = False
SCRAPER_RUNNING = False
SCRAPER_TASK = ""
LOG_BUFFER: deque[str] = deque(maxlen=500)
APP_DIR: Path = Path(".")


class MemoryLogHandler(logging.Handler):
    def emit(self, record):
        try:
            LOG_BUFFER.append(self.format(record))
        except Exception:
            pass


log = logging.getLogger("igbot")
