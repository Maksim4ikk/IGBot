"""Точка входу для Android (Chaquopy): Kotlin-клас BotEngine викликає лише ці функції."""
from igbot.controller import (
    start_scraper_thread,
    start_worker_thread,
    stop_scraper_thread,
    stop_worker_thread,
)
from igbot.runtime import (
    clear_counter,
    clear_logs,
    get_dashboard_json,
    get_logs_json,
    get_settings_json,
    import_targets_json,
    init_android_env,
    logout_account,
    register_account,
    save_settings_json,
    unlock_all,
)

__all__ = [
    "clear_counter", "clear_logs", "get_dashboard_json", "get_logs_json", "get_settings_json",
    "import_targets_json", "init_android_env", "logout_account", "register_account",
    "save_settings_json", "start_scraper_thread", "start_worker_thread", "stop_scraper_thread",
    "stop_worker_thread", "unlock_all",
]
