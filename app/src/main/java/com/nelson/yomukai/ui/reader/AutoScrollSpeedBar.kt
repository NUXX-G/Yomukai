package com.nelson.yomukai.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.i18n.tr
import java.util.Locale

/** Auto-scroll speeds the controls step through: each step is 15 % faster or slower. */
object AutoScrollSpeed {
    const val MIN = 0.5f
    const val MAX = 25f
    private const val STEP = 1.15f

    fun faster(speed: Float) = (speed * STEP).coerceIn(MIN, MAX)
    fun slower(speed: Float) = (speed / STEP).coerceIn(MIN, MAX)

    /** "×1,5": the speed against the default of 3. */
    fun label(speed: Float, base: Float = 3f): String {
        val f = speed / base
        return "×" + String.format(Locale("es"), if (f < 1f) "%.2f" else "%.1f", f).trimEnd('0').trimEnd(',', '.')
    }
}

/** Slower / speed / faster / stop, floating over the page while the reader scrolls by itself. */
@Composable
fun AutoScrollSpeedBar(speed: Float, onSpeed: (Float) -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(50))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { onSpeed(AutoScrollSpeed.slower(speed)) }, enabled = speed > AutoScrollSpeed.MIN) {
            Icon(Icons.Default.Remove, contentDescription = tr("Más lento"), tint = Color.White)
        }
        Text(
            AutoScrollSpeed.label(speed),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.widthIn(min = 52.dp)
        )
        IconButton(onClick = { onSpeed(AutoScrollSpeed.faster(speed)) }, enabled = speed < AutoScrollSpeed.MAX) {
            Icon(Icons.Default.Add, contentDescription = tr("Más rápido"), tint = Color.White)
        }
        IconButton(onClick = onStop) {
            Icon(Icons.Default.Pause, contentDescription = tr("Parar desplazamiento automático"), tint = Color.White)
        }
    }
}
