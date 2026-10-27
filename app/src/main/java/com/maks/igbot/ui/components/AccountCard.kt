package com.maks.igbot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.igbot.ui.theme.AppSuccess
import com.maks.igbot.ui.theme.BrandAccent
import com.maks.igbot.ui.theme.BrandPrimaryDark

/** Картка акаунта: аватар з першою літерою нікнейма, статус підключення, вхід/вихід і проксі. */
@Composable
fun AccountCard(
    title: String,
    username: String,
    loggedIn: Boolean,
    proxy: String,
    onProxyChange: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(if (loggedIn) BrandAccent else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (loggedIn) (username.firstOrNull()?.uppercase() ?: "?") else "?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = if (loggedIn) BrandPrimaryDark else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    if (loggedIn) "@$username" else "Акаунт не підключено",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusPill(
                text = if (loggedIn) "Підключено" else "Немає входу",
                color = if (loggedIn) AppSuccess else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (loggedIn) "Змінити акаунт" else "Увійти через Instagram")
        }

        if (loggedIn) {
            TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text("Вийти з акаунта", color = MaterialTheme.colorScheme.error)
            }
        }

        OutlinedTextField(
            value = proxy,
            onValueChange = onProxyChange,
            label = { Text("Проксі (http://user:pass@ip:port — необов'язково)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}
