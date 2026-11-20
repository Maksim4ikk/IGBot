package com.maks.igbot.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.random.Random

/** Ключі списків (вони ж — назви екранів у навігації вкладки «Акаунти»). */
object FriendsKind {
    const val FOLLOWERS = "followers"                    // хто підписаний на мене
    const val FOLLOWING = "following"                    // на кого підписаний я
    const val NOT_FOLLOWED_BY_ME = "not_followed_by_me"  // вони підписані на мене, а я на них — ні
    const val NOT_FOLLOWING_ME = "not_following_me"      // я на них підписаний, а вони на мене — ні
}

data class FriendUser(val pk: Long, val username: String, val fullName: String)

data class FriendsSnapshot(
    val username: String,
    val fullName: String,
    val bio: String,
    val picUrl: String,
    val followersHeader: Int,
    val followingHeader: Int,
    val followers: List<FriendUser>,
    val following: List<FriendUser>,
    /** false — Instagram віддав списки не повністю; взаємність тоді може бути неточною. */
    val complete: Boolean,
    val warning: String,
    val updatedAt: Long
)

data class BatchState(val total: Int, val done: Int, val failed: Int)

data class FriendsUiState(
    val snapshot: FriendsSnapshot? = null,
    val followerPks: Set<Long> = emptySet(),
    val followingPks: Set<Long> = emptySet(),
    val requestedPks: Set<Long> = emptySet(),
    val busyPks: Set<Long> = emptySet(),
    val loading: Boolean = false,
    val progress: String = "",
    val batch: BatchState? = null
)

// ── похідні значення (завжди рахуються з поточних множин, тому оновлюються після кожної дії) ──
fun FriendsUiState.followersCount(): Int =
    snapshot?.let { if (it.complete) followerPks.size else it.followersHeader } ?: 0

fun FriendsUiState.followingCount(): Int =
    snapshot?.let { if (it.complete) followingPks.size else it.followingHeader } ?: 0

/** Підписані на мене, а я на них — ні. */
fun FriendsUiState.notFollowedByMeCount(): Int = followerPks.count { it !in followingPks }

/** Я підписаний, а вони на мене — ні. */
fun FriendsUiState.notFollowingMeCount(): Int = followingPks.count { it !in followerPks }

/** Склад списку на момент виклику (екран робить «знімок» при вході, щоб рядки не зникали одразу після дії). */
fun FriendsUiState.listFor(kind: String): List<FriendUser> {
    val snap = snapshot ?: return emptyList()
    return when (kind) {
        FriendsKind.FOLLOWERS -> snap.followers
        FriendsKind.FOLLOWING -> snap.following.filter { it.pk in followingPks }
        FriendsKind.NOT_FOLLOWING_ME -> snap.following.filter { it.pk in followingPks && it.pk !in followerPks }
        FriendsKind.NOT_FOLLOWED_BY_ME -> snap.followers.filter { it.pk !in followingPks }
        else -> emptyList()
    }
}

/**
 * Єдине місце, що тримає дані «Мого профілю». Має власний scope (як WebFetcher), тому довге оновлення
 * чи пакетна відписка не обриваються при перемиканні вкладок. Усі виклики Python — з Dispatchers.IO.
 */
object FriendsRepository {
    private const val BATCH_PAUSE_MIN_MS = 4_000L
    private const val BATCH_PAUSE_MAX_MS = 9_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(FriendsUiState())
    val state: StateFlow<FriendsUiState> = _state.asStateFlow()

    /** Одноразові повідомлення для Toast. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    @Volatile
    private var refreshJob: Job? = null

    @Volatile
    private var batchJob: Job? = null

    private fun emit(text: String) {
        _events.tryEmit(text)
    }

    // ── завантаження ────────────────────────────────────────────────
    /** Показати збережені дані; якщо їх ще немає (або змінили акаунт) — завантажити з Instagram. */
    fun open(context: Context) {
        val app = context.applicationContext
        if (refreshJob?.isActive == true || batchJob?.isActive == true) return
        scope.launch {
            val cached = try {
                BotEngine.friendsCached(app)
            } catch (e: Exception) {
                null
            }
            val current = _state.value.snapshot
            if (cached == null) {
                if (current != null) _state.value = FriendsUiState()
                refresh(app)
            } else {
                val snap = parse(cached)
                if (current == null || current.username != snap.username || current.updatedAt != snap.updatedAt) {
                    apply(snap)
                }
            }
        }
    }

    /** Повне оновлення: профіль + усі підписники + усі підписки. На великих акаунтах — кілька хвилин. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (refreshJob?.isActive == true || batchJob?.isActive == true) return
        refreshJob = scope.launch {
            _state.update { it.copy(loading = true, progress = "Підключаюсь…") }
            val poll = launch {
                while (isActive) {
                    delay(1000)
                    try {
                        val p = BotEngine.friendsProgress(app)
                        val done = p.optInt("done")
                        val total = p.optInt("total")
                        val text = buildString {
                            append(p.optString("phase"))
                            if (done > 0) {
                                append(": ").append(done)
                                if (total > 0) append(" / ").append(total)
                            }
                        }
                        _state.update { it.copy(progress = text) }
                    } catch (_: Exception) {
                    }
                }
            }
            try {
                val res = BotEngine.friendsRefresh(app)
                if (res.optBoolean("ok")) {
                    BotEngine.friendsCached(app)?.let { apply(parse(it)) }
                } else {
                    emit(res.optString("error", "Не вдалося оновити"))
                }
            } catch (e: Exception) {
                emit("Помилка: ${e.message}")
            } finally {
                poll.cancel()
                _state.update { it.copy(loading = false, progress = "") }
            }
        }
    }

    private fun apply(snap: FriendsSnapshot) {
        _state.update {
            it.copy(
                snapshot = snap,
                followerPks = snap.followers.mapTo(HashSet()) { u -> u.pk },
                followingPks = snap.following.mapTo(HashSet()) { u -> u.pk },
                requestedPks = emptySet(),
                busyPks = emptySet()
            )
        }
    }

    private fun parse(o: JSONObject): FriendsSnapshot {
        fun users(key: String): List<FriendUser> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return List(a.length()) { i ->
                val r = a.getJSONArray(i)
                FriendUser(r.getLong(0), r.optString(1), r.optString(2))
            }
        }
        return FriendsSnapshot(
            username = o.optString("username"),
            fullName = o.optString("full_name"),
            bio = o.optString("bio"),
            picUrl = o.optString("pic"),
            followersHeader = o.optInt("followers_count"),
            followingHeader = o.optInt("following_count"),
            followers = users("followers"),
            following = users("following"),
            complete = o.optBoolean("complete", true),
            warning = o.optString("warning"),
            updatedAt = o.optLong("updated_at")
        )
    }

    // ── підписатись / відписатись ───────────────────────────────────
    /** Один акаунт: якщо я підписаний — відписатись, інакше підписатись. */
    fun toggle(context: Context, user: FriendUser) {
        val app = context.applicationContext
        val st = _state.value
        if (st.loading || st.batch != null || user.pk in st.busyPks) return
        val unfollow = user.pk in st.followingPks
        _state.update { it.copy(busyPks = it.busyPks + user.pk) }
        scope.launch {
            try {
                val res = act(app, unfollow, user)
                when (res.optString("status")) {
                    "ok" -> Unit
                    "requested" -> emit("@${user.username}: акаунт приватний — запит на підписку надіслано")
                    "blocked" -> emit("Instagram заблокував дію. Зачекайте кілька годин.")
                    "rate_limited" -> emit("Забагато запитів (429). Спробуйте пізніше.")
                    "login" -> emit("Сесія недійсна — увійдіть в акаунт знову.")
                    else -> emit("Не вдалося: ${res.optString("detail")}")
                }
            } finally {
                _state.update { it.copy(busyPks = it.busyPks - user.pk) }
            }
        }
    }

    private fun act(app: Context, unfollow: Boolean, user: FriendUser): JSONObject {
        val res = try {
            BotEngine.friendsAction(app, if (unfollow) "unfollow" else "follow", user.pk, user.username)
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("detail", e.message ?: "помилка")
        }
        when (res.optString("status")) {
            "ok" -> _state.update { it.withFollowing(user, !unfollow) }
            "requested" -> _state.update { it.copy(requestedPks = it.requestedPks + user.pk) }
        }
        return res
    }

    private fun FriendsUiState.withFollowing(user: FriendUser, nowFollowing: Boolean): FriendsUiState {
        val pks = if (nowFollowing) followingPks + user.pk else followingPks - user.pk
        val snap = snapshot
        val newSnap =
            if (nowFollowing && snap != null && snap.following.none { it.pk == user.pk }) {
                snap.copy(following = listOf(user) + snap.following)
            } else {
                snap
            }
        return copy(followingPks = pks, snapshot = newSnap, requestedPks = requestedPks - user.pk)
    }

    // ── пакетна відписка ────────────────────────────────────────────
    /**
     * Відписатись від перших [limit] акаунтів зі списку [queue] (у тому порядку, в якому вони на екрані),
     * від кого я ще підписаний. Між відписками — випадкова пауза 4–9 с. Зупиняється сама при блокуванні
     * Instagram, 429, виході з сесії або 3 помилках поспіль.
     */
    fun unfollowBatch(context: Context, queue: List<FriendUser>, limit: Int = 50) {
        val app = context.applicationContext
        if (batchJob?.isActive == true || _state.value.loading) return
        val targets = queue.filter { it.pk in _state.value.followingPks }.take(limit)
        if (targets.isEmpty()) {
            emit("У списку немає акаунтів, від яких можна відписатись")
            return
        }
        batchJob = scope.launch {
            var done = 0
            var failed = 0
            var streak = 0
            var note = ""
            _state.update { it.copy(batch = BatchState(targets.size, 0, 0)) }
            try {
                for ((i, user) in targets.withIndex()) {
                    ensureActive()
                    _state.update { it.copy(busyPks = it.busyPks + user.pk) }
                    val res = act(app, true, user)
                    _state.update { it.copy(busyPks = it.busyPks - user.pk) }
                    when (val st = res.optString("status")) {
                        "ok" -> {
                            done++
                            streak = 0
                        }
                        "blocked", "rate_limited", "login" -> {
                            note = stopReason(st)
                            break
                        }
                        else -> {
                            failed++
                            streak++
                            if (streak >= 3) {
                                note = "3 помилки поспіль: ${res.optString("detail")}"
                                break
                            }
                        }
                    }
                    _state.update { it.copy(batch = BatchState(targets.size, done, failed)) }
                    if (i < targets.lastIndex) delay(Random.nextLong(BATCH_PAUSE_MIN_MS, BATCH_PAUSE_MAX_MS))
                }
            } catch (e: CancellationException) {
                note = "Зупинено вами."
                throw e
            } finally {
                _state.update { it.copy(batch = null, busyPks = emptySet()) }
                emit(
                    "Відписано: $done" +
                        (if (failed > 0) ", помилок: $failed" else "") +
                        (if (note.isNotEmpty()) ". $note" else "")
                )
            }
        }
    }

    fun stopBatch() {
        batchJob?.cancel()
    }

    private fun stopReason(status: String): String = when (status) {
        "blocked" -> "Instagram заблокував дію (Action Block) — зупинено. Зачекайте кілька годин."
        "rate_limited" -> "Забагато запитів (429) — зупинено. Спробуйте пізніше."
        else -> "Сесія недійсна — увійдіть в акаунт знову."
    }
}
