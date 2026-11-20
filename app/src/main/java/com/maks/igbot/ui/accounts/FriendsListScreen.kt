package com.maks.igbot.ui.accounts

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.igbot.data.FriendUser
import com.maks.igbot.data.FriendsKind
import com.maks.igbot.data.FriendsRepository
import com.maks.igbot.data.listFor
import com.maks.igbot.ui.components.AppCard
import com.maks.igbot.ui.components.ConfirmDialog
import com.maks.igbot.ui.components.InfoNote
import com.maks.igbot.ui.theme.BrandAccent
import com.maks.igbot.ui.theme.BrandPrimaryDark

private const val BATCH_SIZE = 50

// ════════════════════════════════════════════════════════════════════
// Список: підписники / підписки / взаємність
// ════════════════════════════════════════════════════════════════════
@Composable
fun FriendsListScreen(context: Context, kind: String, onBack: () -> Unit) {
    val state by FriendsRepository.state.collectAsState()
    LaunchedEffect(Unit) { FriendsRepository.open(context) }
    val snap = state.snapshot

    val (title, subtitle) = when (kind) {
        FriendsKind.FOLLOWERS -> "Підписники" to "Хто підписаний на мене"
        FriendsKind.FOLLOWING -> "Підписки" to "На кого підписаний я"
        FriendsKind.NOT_FOLLOWED_BY_ME -> "Я не підписаний взаємно" to "Вони підписані на мене, а я на них — ні"
        else -> "На мене не підписані взаємно" to "Я на них підписаний, а вони на мене — ні"
    }

    // «Знімок» складу списку на момент входу на екран: після підписки/відписки рядок не зникає,
    // а лише змінює кнопку (так можна одразу скасувати). Перераховується після оновлення даних.
    val rows = remember(kind, snap?.updatedAt) { FriendsRepository.state.value.listFor(kind) }

    val canBatch = kind == FriendsKind.FOLLOWING || kind == FriendsKind.NOT_FOLLOWING_ME
    // Список «не підписані на мене» — це різниця двох списків: якщо один із них неповний, вона бреше
    // і можна відписатись від людей, які насправді підписані. Тому масову відписку тут вимикаємо.
    val unreliable = kind == FriendsKind.NOT_FOLLOWING_ME && snap != null && !snap.complete
    val remaining = remember(rows, state.followingPks) { rows.count { it.pk in state.followingPks } }
    val n = minOf(BATCH_SIZE, remaining)

    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        ConfirmDialog(
            title = "Відписатись від $n акаунтів?",
            text = "Бот відпишеться від перших $n акаунтів цього списку. Між відписками пауза 4–9 с " +
                "(орієнтовно ${(n * 6.5 / 60).toInt().coerceAtLeast(1)} хв). Зупинити можна будь-коли.",
            confirmLabel = "Відписатись",
            onConfirm = {
                confirm = false
                FriendsRepository.unfollowBatch(context, rows, BATCH_SIZE)
            },
            onDismiss = { confirm = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTopBar(title, "$subtitle · ${formatCount(rows.size)}", onBack)

        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (state.progress.isNotBlank()) {
                Text(state.progress, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (unreliable) {
            InfoNote(
                "Списки підписників і підписок завантажені не повністю, тому цей список може бути неточним. " +
                    "Масова відписка вимкнена — оновіть дані на екрані профілю.",
                emoji = "⚠️"
            )
        }

        if (canBatch) {
            val batch = state.batch
            if (batch == null) {
                Button(
                    onClick = { confirm = true },
                    enabled = n > 0 && !state.loading && !unreliable,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(if (n > 0) "Відписатись від $n акаунтів" else "Немає від кого відписуватись")
                }
            } else {
                AppCard {
                    Text("Відписую… ${batch.done} із ${batch.total}", fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(
                        progress = { if (batch.total > 0) batch.done.toFloat() / batch.total else 0f },
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    Text(
                        "Пауза 4–9 с між відписками" + if (batch.failed > 0) " · помилок: ${batch.failed}" else "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { FriendsRepository.stopBatch() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Зупинити", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (state.loading) "Завантажую…" else "Список порожній",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { it.pk }) { user ->
                    FriendRow(
                        user = user,
                        following = user.pk in state.followingPks,
                        requested = user.pk in state.requestedPks,
                        busy = user.pk in state.busyPks,
                        enabled = !state.loading && state.batch == null,
                        onClick = { FriendsRepository.toggle(context, user) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendRow(
    user: FriendUser,
    following: Boolean,
    requested: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).background(BrandAccent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                user.username.firstOrNull()?.uppercase() ?: "?",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = BrandPrimaryDark
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "@${user.username}",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (user.fullName.isNotBlank()) {
                Text(
                    user.fullName,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val buttonPadding = PaddingValues(horizontal = 14.dp)
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            requested -> Text("Запит надіслано", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            following -> OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                contentPadding = buttonPadding,
                modifier = Modifier.height(36.dp)
            ) {
                Text("Відписатись", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
            else -> Button(
                onClick = onClick,
                enabled = enabled,
                contentPadding = buttonPadding,
                modifier = Modifier.height(36.dp)
            ) {
                Text("Підписатись", fontSize = 13.sp)
            }
        }
    }
}
