package com.nelson.yomukai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.data.sync.SyncState
import kotlinx.coroutines.launch

/**
 * Optional sync with a Yomukai server. Off by default: nothing changes until the user connects.
 */
@Composable
fun ServerSyncItem(
    state: SyncState,
    onConnect: suspend (server: String, user: String, password: String) -> String?,
    onSyncNow: () -> Unit,
    onDisconnect: () -> Unit
) {
    var connecting by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.connected) {
            Text(
                "Guarda tu biblioteca, categorías, progreso, historial, marcadores, fuentes y géneros excluidos en tu servidor de Yomukai, " +
                    "y tenlos iguales en la web y en otros móviles. Es opcional: sin conectarte, todo se queda solo en este móvil.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { connecting = true }) { Text("Conectar con mi servidor") }
        } else {
            Text("Conectado como ${state.user}", style = MaterialTheme.typography.titleSmall)
            Text(state.server.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val last = if (state.lastSyncAt > 0) "Última vez: " + ago(state.lastSyncAt) else "Aún no se ha sincronizado"
            Text(last, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onSyncNow, enabled = !state.running) { Text(if (state.running) "Sincronizando..." else "Sincronizar ahora") }
                TextButton(onClick = { confirmDisconnect = true }) { Text("Desconectar") }
                if (state.running) CircularProgressIndicator(Modifier.padding(start = 4.dp), strokeWidth = 2.dp)
            }
        }
    }

    if (connecting) ConnectDialog(onConnect = onConnect, onDismiss = { connecting = false })

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("¿Desconectar?") },
            text = { Text("Lo que tienes en este móvil se queda aquí, y lo del servidor allí. Dejarán de sincronizarse.") },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) { Text("Desconectar") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ConnectDialog(onConnect: suspend (String, String, String) -> String?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Android 17+ asks before an app talks to devices on the home network (a server on a laptop, a NAS).
    val localNetwork = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { afterPermission?.invoke(); afterPermission = null }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Conectar con tu servidor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "La dirección de tu web de Yomukai y tu usuario y contraseña de allí. Lo que ya tienes en este móvil se junta con lo del servidor; no se borra nada.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = server, onValueChange = { server = it }, singleLine = true, label = { Text("Servidor") },
                    placeholder = { Text("https://yomukai.tudominio.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it }, singleLine = true, label = { Text("Usuario") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true, label = { Text("Contraseña") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text("Conectando y juntando tus datos...", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && server.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    busy = true
                    error = null
                    val go = {
                        scope.launch {
                            val problem = onConnect(server, user, password)
                            busy = false
                            if (problem == null) onDismiss()
                            else error = if (needsLocalNetwork(context, server)) problem + ". Permite a Yomukai ver los dispositivos de tu red local en los ajustes de Android." else problem
                        }
                        Unit
                    }
                    if (needsLocalNetwork(context, server)) { afterPermission = go; localNetwork.launch(LOCAL_NETWORK) } else go()
                }
            ) { Text("Conectar") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** "hace un momento", "hace 5 min", "hace 2 h", "hace 3 días", in Spanish whatever the phone's language. */
private fun ago(at: Long): String {
    val minutes = (System.currentTimeMillis() - at).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "hace un momento"
        minutes < 60 -> "hace $minutes min"
        minutes < 24 * 60 -> "hace ${minutes / 60} h"
        else -> (minutes / (24 * 60)).let { d -> if (d == 1L) "hace 1 día" else "hace $d días" }
    }
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Android 17+, a server on the home network, and the permission not granted yet. */
private fun needsLocalNetwork(context: android.content.Context, server: String): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 37) return false
    if (androidx.core.content.ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) == android.content.pm.PackageManager.PERMISSION_GRANTED) return false
    val host = com.nelson.yomukai.data.sync.ServerSync.normalizeServer(server)?.let { java.net.URI(it).host }?.lowercase() ?: return false
    return isLocalHost(host)
}

/** Private addresses and home-network names: 192.168.x.x, 10.x, 172.16-31.x, *.local, a name without dots. */
internal fun isLocalHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") || !host.contains('.')) return true
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) ||
        (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 169 && parts[1] == 254) || (parts[0] == 100 && parts[1] in 64..127)
}
