package com.nelson.yomukai.ui.reader

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.nelson.yomukai.i18n.tr

/**
 * Some phones stop apps that run with the screen off even when they play audio. Reading aloud asks once (explaining
 * why) to take Yomukai out of battery optimization; the user decides in the system's own screen.
 */
object BatteryOptimization {
    private const val PREFS = "ui"
    private const val ASKED = "battery_asked"

    fun shouldAsk(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(context.packageName)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false)) return false
        prefs.edit().putBoolean(ASKED, true).apply()
        return true
    }
}

@Composable
fun BatteryOptimizationDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Leer con la pantalla apagada")) },
        text = {
            Text(
                tr("Para que la lectura no se corte al apagar la pantalla, algunos móviles necesitan que Yomukai no esté en ") +
                    tr("«optimización de batería». Solo gasta batería mientras lee. Busca Yomukai en la lista y elige «No optimizar».")
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(tr("Abrir ajustes")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Ahora no")) } }
    )
}
