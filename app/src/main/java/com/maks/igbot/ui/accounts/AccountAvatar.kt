package com.maks.igbot.ui.accounts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.maks.igbot.ui.theme.BrandAccent
import com.maks.igbot.ui.theme.BrandPrimaryDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private object AvatarBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun get(url: String): Bitmap? = cache.get(url)

    fun put(url: String, bitmap: Bitmap) {
        cache.put(url, bitmap)
    }
}

/** Завантажує фото профілю у фоні, кешує його та показує літеру, якщо фото ще немає. */
@Composable
fun AccountAvatar(url: String, letter: String, size: Dp) {
    var bitmap by remember(url) {
        mutableStateOf(if (url.isBlank()) null else AvatarBitmapCache.get(url))
    }

    LaunchedEffect(url) {
        if (bitmap == null && url.startsWith("https://")) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.instanceFollowRedirects = true
                    try {
                        connection.inputStream.use { BitmapFactory.decodeStream(it) }
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()?.also { AvatarBitmapCache.put(url, it) }
            }
        }
    }

    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(BrandAccent),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                letter,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
                color = BrandPrimaryDark
            )
        }
    }
}
