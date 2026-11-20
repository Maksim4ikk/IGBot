package com.maks.igbot.ui.accounts

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.igbot.data.FriendsKind
import com.maks.igbot.data.FriendsRepository
import com.maks.igbot.data.followersCount
import com.maks.igbot.data.followingCount
import com.maks.igbot.data.notFollowedByMeCount
import com.maks.igbot.data.notFollowingMeCount
import com.maks.igbot.ui.components.AppCard
import com.maks.igbot.ui.components.InfoNote
import com.maks.igbot.ui.theme.AppSuccess
import com.maks.igbot.ui.theme.AppWarning
import com.maks.igbot.ui.theme.BrandAccent
import com.maks.igbot.ui.theme.BrandPrimaryDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// ════════════════════════════════════════════════════════════════════
// Профіль основного акаунта (як сторінка Instagram, у стилі додатка)
// ════════════════════════════════════════════════════════════════════
@Composable
fun MyProfileScreen(context: Context, onBack: () -> Unit, onOpenList: (String) -> Unit) {
    val state by FriendsRepository.state.collectAsState()
    LaunchedEffect(Unit) { FriendsRepository.open(context) }
    val snap = state.snapshot

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTopBar(
            title = "Мій профіль",
            subtitle = if (snap != null) "@${snap.username}" else "Основний акаунт",
            onBack = onBack
        )

        if (snap == null) {
            AppCard {
                if (state.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Завантажую підписників і підписки…", fontWeight = FontWeight.SemiBold)
                    }
                    if (state.progress.isNotBlank()) {
                        Text(state.progress, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    InfoNote("На великих акаунтах це може тривати кілька хвилин. Хід роботи видно також у «Подіях».")
                } else {
                    Text("Дані ще не завантажено", fontWeight = FontWeight.SemiBold)
                    Button(onClick = { FriendsRepository.refresh(context) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("Завантажити")
                    }
                }
            }
        } else {
            // ── шапка: аватар, підписники, підписки ─────────────────
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(url = snap.picUrl, letter = snap.username.firstOrNull()?.uppercase() ?: "?", size = 84.dp)
                    Spacer(Modifier.width(8.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatBlock(state.followersCount(), "Підписники") { onOpenList(FriendsKind.FOLLOWERS) }
                        StatBlock(state.followingCount(), "Підписки") { onOpenList(FriendsKind.FOLLOWING) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("@${snap.username}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (snap.fullName.isNotBlank()) Text(snap.fullName, fontWeight = FontWeight.Medium)
                    if (snap.bio.isNotBlank()) {
                        Text(
                            snap.bio,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ── взаємність ──────────────────────────────────────────
            AppCard {
                Text("Взаємність", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MutualTile(
                        value = state.notFollowedByMeCount(),
                        label = "Я не підписаний взаємно",
                        hint = "вони на мене підписані",
                        color = AppSuccess,
                        modifier = Modifier.weight(1f)
                    ) { onOpenList(FriendsKind.NOT_FOLLOWED_BY_ME) }
                    MutualTile(
                        value = state.notFollowingMeCount(),
                        label = "На мене не підписані взаємно",
                        hint = "я на них підписаний",
                        color = AppWarning,
                        modifier = Modifier.weight(1f)
                    ) { onOpenList(FriendsKind.NOT_FOLLOWING_ME) }
                }
                if (!snap.complete) {
                    InfoNote(
                        "Instagram віддав списки не повністю (${snap.warning}). Взаємність може бути неточною — " +
                            "масова відписка на екрані «не підписані на мене» вимкнена. Спробуйте оновити пізніше.",
                        emoji = "⚠️"
                    )
                }
            }

            // ── оновлення ───────────────────────────────────────────
            AppCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Оновлено ${ago(snap.updatedAt)}", fontSize = 14.sp)
                        if (state.loading && state.progress.isNotBlank()) {
                            Text(state.progress, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                    IconButton(onClick = { FriendsRepository.refresh(context) }, enabled = !state.loading && state.batch == null) {
                        Icon(Icons.Default.Refresh, contentDescription = "Оновити")
                    }
                }
                if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StatBlock(value: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(formatCount(value), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MutualTile(
    value: Int,
    label: String,
    hint: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(formatCount(value), fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = color)
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Аватар: справжнє фото профілю, а поки воно вантажиться (або не вдалося) — перша літера нікнейма. */
@Composable
private fun ProfileAvatar(url: String, letter: String, size: Dp) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.startsWith("https://")) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                }.getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(BrandAccent),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(letter, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = BrandPrimaryDark)
        }
    }
}

private fun ago(ts: Long): String {
    val s = System.currentTimeMillis() / 1000 - ts
    return when {
        s < 90 -> "щойно"
        s < 3600 -> "${s / 60} хв тому"
        s < 86400 -> "${s / 3600} год тому"
        else -> "${s / 86400} дн тому"
    }
}
