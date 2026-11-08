package com.nelson.yomukai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.i18n.tr

/** One part of the guide: what it is and how to do it, in a few steps. */
private class GuidePart(val title: String, val what: String, val steps: List<String>)

/**
 * «Guía rápida»: everything Yomukai does, said simply, the same as the web's /guia (same texts, so the same
 * translations). For friends who will never read a README.
 */
@Composable
fun GuideDialog(onDismiss: () -> Unit) {
    val parts = listOf(
        GuidePart(
            tr("Encontrar qué leer o ver"),
            tr("Cada obra sale una sola vez, en tu idioma, aunque esté en varias webs. Yomukai elige la mejor fuente por ti."),
            listOf(
                tr("Pulsa una portada: se abre la obra con todos sus capítulos."),
                tr("¿La quieres en otro idioma? Arriba de la ficha tienes los idiomas disponibles.")
            )
        ),
        GuidePart(
            tr("Leer manga, manhwa y cómics"),
            tr("Pasa páginas o baja en tira larga. Toca el centro de la pantalla para ver u ocultar los botones."),
            listOf(
                tr("En tira larga, desplazamiento automático: botón ▶ abajo; va más despacio donde hay texto."),
                tr("Marcador, índice y ajustes de lectura: arriba a la derecha.")
            )
        ),
        GuidePart(
            tr("Novelas con voz"),
            tr("Cualquier novela se puede escuchar como un audiolibro, con voces distintas para el narrador y los personajes."),
            listOf(tr("Abre un capítulo y pulsa el altavoz de arriba."), tr("La velocidad, en el mismo sitio."))
        ),
        GuidePart(
            tr("Anime y salas con amigos"),
            tr("Mira anime en Yomukai y, si quieres, a la vez que tus amigos: el vídeo va sincronizado y hay chat y reacciones."),
            listOf(
                tr("En la ficha de un anime pulsa «Ver con amigos»."),
                tr("Se copia el enlace de tu sala: pásalo por WhatsApp o Discord."),
                tr("Quien lo abra entra contigo; si alguien pausa, se pausa para todos.")
            )
        ),
        GuidePart(
            tr("Tu biblioteca y avisos"),
            tr("Guarda lo que sigues con el corazón de la ficha. Te avisa (campana) cuando sale un capítulo nuevo y se sincroniza con la app."),
            listOf(tr("Tus estados (leyendo, pendiente, terminado) y tu historial, en Biblioteca."))
        ),
        GuidePart(
            tr("Aprender idiomas"),
            tr("Lee en el idioma que estudias: toca una palabra para ver qué significa, escucharla y guardarla. Luego repásala con tarjetas."),
            listOf(tr("Activa el modo aprendizaje (botón de abajo o en Ajustes)."), tr("En una novela, mantén pulsada una palabra; en manga, un bocadillo."))
        ),
        GuidePart(
            tr("Ajustes útiles"),
            tr("Idioma de la interfaz, temas, qué fuentes usar y la app de Android."),
            listOf(tr("¿Vienes de AniList, MyAnimeList o Mihon? En Ajustes › Importar de otra app traes tu lista."))
        )
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Entendido")) } },
        title = { Text(tr("Guía rápida")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Todo lo que puedes hacer en Yomukai, en un minuto."), style = MaterialTheme.typography.bodyMedium)
                parts.forEach { part ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(part.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(part.what, style = MaterialTheme.typography.bodySmall)
                            part.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    )
}
