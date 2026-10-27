"""Сумісність: боти розбито на base_bot.py, scraper_bot.py та worker_bot.py."""
from .base_bot import BaseBot
from .scraper_bot import ScraperBot
from .worker_bot import MainWorker

__all__ = ["BaseBot", "ScraperBot", "MainWorker"]
