package com.maks.igbot.ui.logs

/** Тип запису журналу — від нього залежить колір і фільтри. */
enum class LogKind { INFO, LIKE, PARSER, WORKER, WARNING, ERROR }

data class LogEntry(val time: String, val level: Char, val message: String) {
    val kind: LogKind
        get() = when {
            level == 'E' -> LogKind.ERROR
            level == 'W' -> LogKind.WARNING
            message.contains("❤️") -> LogKind.LIKE
            message.startsWith("[Парсер") -> LogKind.PARSER
            message.startsWith("[Воркер") -> LogKind.WORKER
            else -> LogKind.INFO
        }
}

/** Рядок журналу Python має вигляд «ГГ:ХХ:СС|І|текст», де І — рівень (I, W, E). */
fun parseLogLine(raw: String): LogEntry {
    val p = raw.split("|", limit = 3)
    return if (p.size == 3 && p[1].length == 1) LogEntry(p[0], p[1][0], p[2]) else LogEntry("", 'I', raw)
}
