package com.nelson.yomukai.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.BuildConfig
import com.nelson.yomukai.LanguagePrefs
import com.nelson.yomukai.data.update.AppUpdates
import com.nelson.yomukai.data.update.AvailableUpdate
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.LocalAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DAY_MS = 24 * 3_600_000L

/**
 * "Versión X disponible": asks GitHub at most once a day and keeps the answer, so the notice stays until it is
 * installed or dismissed for that version. Opens the APK (or the release page) in the browser; nothing installs alone.
 */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val prefs = remember { context.getSharedPreferences(LanguagePrefs.FILE, Context.MODE_PRIVATE) }
    var update by remember { mutableStateOf<AvailableUpdate?>(saved(prefs)) }
    LaunchedEffect(Unit) {
        if (System.currentTimeMillis() - prefs.getLong("update_checked", 0) < DAY_MS) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) { runCatching { AppUpdates.check(container.network, BuildConfig.VERSION_NAME, android.os.Build.SUPPORTED_ABIS.toList()) }.getOrNull() }
        prefs.edit().putLong("update_checked", System.currentTimeMillis())
            .putString("update_version", found?.version).putString("update_url", found?.apkUrl ?: found?.pageUrl).apply()
        update = found
    }
    val shown = update?.takeIf { it.version != prefs.getString("update_dismissed", null) && AppUpdates.isNewer(it.version, BuildConfig.VERSION_NAME) } ?: return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Versión {0} disponible", shown.version), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                prefs.edit().putString("update_dismissed", shown.version).apply()
                update = null
            }) { Text(tr("Ahora no")) }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(shown.apkUrl ?: shown.pageUrl))) }
            }) { Text(tr("Descargar")) }
        }
    }
}

private fun saved(prefs: android.content.SharedPreferences): AvailableUpdate? {
    val version = prefs.getString("update_version", null) ?: return null
    val url = prefs.getString("update_url", null) ?: return null
    return AvailableUpdate(version, url, url.takeIf { it.endsWith(".apk") })
}
