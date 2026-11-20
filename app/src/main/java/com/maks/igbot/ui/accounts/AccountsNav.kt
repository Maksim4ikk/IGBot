package com.maks.igbot.ui.accounts

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maks.igbot.data.FriendsRepository
import com.maks.igbot.ui.components.ScreenHeader
import java.text.NumberFormat

private const val ROUTE_ACCOUNTS = ""
private const val ROUTE_PROFILE = "profile"

/**
 * Вкладка «Акаунти» з вкладеними екранами: список акаунтів → мій профіль → список (підписники,
 * підписки, взаємність). Системна кнопка «Назад» повертає на крок назад.
 */
@Composable
fun AccountsScreen(context: Context) {
    var route by rememberSaveable { mutableStateOf(ROUTE_ACCOUNTS) }

    BackHandler(enabled = route != ROUTE_ACCOUNTS) {
        route = if (route == ROUTE_PROFILE) ROUTE_ACCOUNTS else ROUTE_PROFILE
    }

    // Повідомлення від репозиторію (підсумок відписки, помилки Instagram…)
    LaunchedEffect(Unit) {
        FriendsRepository.events.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    when (route) {
        ROUTE_ACCOUNTS -> AccountsMain(context = context, onOpenMain = { route = ROUTE_PROFILE })
        ROUTE_PROFILE -> MyProfileScreen(
            context = context,
            onBack = { route = ROUTE_ACCOUNTS },
            onOpenList = { route = it }
        )
        else -> FriendsListScreen(context = context, kind = route, onBack = { route = ROUTE_PROFILE })
    }
}

/** Верхній рядок вкладеного екрана: стрілка «назад» + заголовок у стилі додатка. */
@Composable
internal fun ScreenTopBar(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
        }
        Box(modifier = Modifier.weight(1f)) { ScreenHeader(title, subtitle) }
    }
}

internal fun formatCount(n: Int): String = NumberFormat.getIntegerInstance().format(n)
