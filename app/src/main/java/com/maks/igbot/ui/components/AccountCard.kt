package com.maks.igbot.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)

            Text(
                if (loggedIn) "Підключено · @$username" else "Не підключено",
                color = if (loggedIn) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )

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
}
