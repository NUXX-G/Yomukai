package com.nelson.yomukai.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.LanguagePrefs
import com.nelson.yomukai.i18n.I18n
import com.nelson.yomukai.i18n.tr

/**
 * Spanish or English for the app's interface (content in any language reads the same). Changing it restarts the app,
 * so every text, labels worked out once included, comes in the new language.
 */
@Composable
fun LanguagePicker() {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            tr("El idioma de la interfaz. Puedes leer y ver contenido en cualquier idioma igual."),
            style = MaterialTheme.typography.bodySmall
        )
        Row(Modifier.padding(top = 8.dp)) {
            listOf(I18n.SPANISH to "Español", I18n.ENGLISH to "English").forEach { (code, name) ->
                FilterChip(selected = I18n.lang == code, onClick = { if (I18n.lang != code) switchLanguage(context, code) }, label = { Text(name) })
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

private fun switchLanguage(context: Context, code: String) {
    context.getSharedPreferences(LanguagePrefs.FILE, Context.MODE_PRIVATE).edit().putString(LanguagePrefs.KEY, code).commit()
    val restart = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
    context.startActivity(restart)
    Runtime.getRuntime().exit(0)
}
