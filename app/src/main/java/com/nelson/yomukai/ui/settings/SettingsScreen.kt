package com.nelson.yomukai.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nelson.yomukai.BuildConfig
import com.nelson.yomukai.data.network.TorPhase
import com.nelson.yomukai.data.prefs.BrowserHome
import com.nelson.yomukai.data.prefs.ComicFormat
import com.nelson.yomukai.data.prefs.MangaReaderMode
import com.nelson.yomukai.data.prefs.NetworkSettings
import com.nelson.yomukai.data.prefs.NovelFormat
import com.nelson.yomukai.data.prefs.ProxyType
import com.nelson.yomukai.data.prefs.ThemeMode
import com.nelson.yomukai.data.prefs.TorMode
import com.nelson.yomukai.data.updates.LibraryUpdateWorker
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.components.InkOutlinedButton
import com.nelson.yomukai.ui.components.LanguageTag
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val context = LocalContext.current
    val app by viewModel.app.collectAsStateWithLifecycle()
    val manga by viewModel.manga.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val serverFeatures by viewModel.features.collectAsStateWithLifecycle()
    val tor by viewModel.tor.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    var confirmHistory by remember { mutableStateOf(false) }
    var showExclusions by remember { mutableStateOf(false) }
    val exclusions by viewModel.exclusions.collectAsStateWithLifecycle()

    if (showExclusions) {
        ExclusionsDialog(exclusions, viewModel.sources, viewModel::toggleExclusion, onDismiss = { showExclusions = false })
    }

    LaunchedEffect(Unit) { viewModel.refreshStorage(context) }

    // Android 13+ needs runtime permission for update notifications.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(tr("Ajustes")) }) }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                var guide by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(tr("¿Qué puedo hacer en Yomukai? Guía rápida")) },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { guide = true }
                )
                if (guide) GuideDialog { guide = false }
            }
            item { GroupTitle(tr("Sincronizar con servidor")) }
            item { ServerSyncItem(sync, viewModel::connectServer, viewModel::syncNow, viewModel::disconnectServer, serverFeatures) }

            item { GroupTitle(tr("Idioma") + " · Language") }
            item { LanguagePicker() }
            item { GroupTitle(tr("Voces naturales en el móvil")) }
            item { DeviceVoicesSection() }
            item { GroupTitle(tr("Aprender idiomas")) }
            item {
                SwitchItem(tr("Modo aprendizaje"), tr("Guarda las palabras que mantengas pulsadas para repasarlas después (traducir funciona siempre)"), app.learningEnabled) { v ->
                    viewModel.updateApp { it.copy(learningEnabled = v) }
                }
            }
            if (app.learningEnabled) item {
                var reviewing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(tr("Repasar palabras")) },
                    supportingContent = { Text(tr("Repaso espaciado de las palabras guardadas (necesita el servidor)")) },
                    modifier = Modifier.clickable { reviewing = true }
                )
                if (reviewing) com.nelson.yomukai.ui.learning.ReviewDialog { reviewing = false }
            }
            item { GroupTitle(tr("Apariencia")) }
            item {
                ChipRow {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = app.themeMode == mode,
                            onClick = { viewModel.updateApp { it.copy(themeMode = mode) } },
                            label = { Text(mode.displayName) }
                        )
                    }
                }
            }
            item {
                // The same themes as the web; with a server connected the choice is saved there too (and comes back on sync).
                val current = remember(app.customTheme) { com.nelson.yomukai.ui.theme.CustomTheme.parse(app.customTheme)?.name }
                val scope = rememberCoroutineScope()
                val container = com.nelson.yomukai.ui.LocalAppContainer.current
                Text(tr("Tema"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                ChipRow {
                    FilterChip(selected = current == null, onClick = { viewModel.updateApp { it.copy(customTheme = null) } }, label = { Text(tr("Ninguno")) })
                    com.nelson.yomukai.ui.theme.CustomTheme.builtIn.forEach { (id, spec) ->
                        FilterChip(
                            selected = current == spec.name,
                            onClick = {
                                viewModel.updateApp { it.copy(customTheme = com.nelson.yomukai.data.theme.ThemeRules.toJson(spec).toString()) }
                                scope.launch {
                                    runCatching { container.serverSync.api("PUT", "/api/themes/use", com.google.gson.JsonObject().apply { addProperty("builtIn", id) }) }
                                }
                            },
                            label = { Text(spec.name) }
                        )
                    }
                }
            }
            item {
                SwitchItem(tr("Color dinámico"), tr("Usa los colores del fondo de pantalla (Android 12+)"), app.dynamicColor) { v ->
                    viewModel.updateApp { it.copy(dynamicColor = v) }
                }
            }

            item { GroupTitle(tr("Lector de manga")) }
            item {
                Text(
                    "Modo predeterminado",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                ChipRow {
                    MangaReaderMode.entries.forEach { mode ->
                        FilterChip(
                            selected = manga.mode == mode,
                            onClick = { viewModel.updateManga { it.copy(mode = mode) } },
                            label = { Text(mode.displayName) }
                        )
                    }
                }
            }

            item { GroupTitle(tr("Actualizaciones")) }
            item {
                SwitchItem(
                    tr("Buscar capítulos nuevos"),
                    tr("Comprueba en segundo plano los títulos de tu biblioteca y te avisa con una notificación"),
                    app.updatesEnabled
                ) { v ->
                    if (v) ensureNotificationPermission()
                    viewModel.updateApp { it.copy(updatesEnabled = v) }
                }
            }
            if (app.updatesEnabled) {
                item {
                    Text(tr("Frecuencia"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    ChipRow {
                        listOf(6, 12, 24, 48).forEach { hours ->
                            FilterChip(
                                selected = app.updateIntervalHours == hours,
                                onClick = { viewModel.updateApp { it.copy(updateIntervalHours = hours) } },
                                label = { Text(tr("Cada {0} h", hours)) }
                            )
                        }
                    }
                }
                item {
                    SwitchItem(tr("Solo con Wi-Fi"), tr("No usar datos móviles para comprobar novedades"), app.updatesWifiOnly) { v ->
                        viewModel.updateApp { it.copy(updatesWifiOnly = v) }
                    }
                }
            }
            item {
                ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(tr("Buscar ahora")) },
                    supportingContent = { Text(tr("Comprueba ya los títulos de tu biblioteca con avisos activos")) },
                    leadingContent = { Icon(Icons.Default.Refresh, null) },
                    modifier = Modifier.clickable {
                        ensureNotificationPermission()
                        LibraryUpdateWorker.runNow(context)
                        Toast.makeText(context, tr("Buscando novedades en segundo plano..."), Toast.LENGTH_SHORT).show()
                    }
                )
            }

            item { GroupTitle(tr("Contenido")) }
            item {
                val total = exclusions.global.size + exclusions.bySource.values.sumOf { it.size }
                ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(tr("Géneros excluidos")) },
                    supportingContent = { Text(if (total == 0) tr("Oculta géneros en todas las fuentes o solo en algunas") else "$total exclusiones activas") },
                    modifier = Modifier.clickable { showExclusions = true }
                )
            }

            item { GroupTitle(tr("Fuentes")) }
            items(viewModel.sources, key = { it.id }) { source ->
                ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(source.name)
                            LanguageTag(source.language)
                        }
                    },
                    supportingContent = {
                        val types = source.supportedTypes.joinToString(", ") { it.displayName }
                        val broken = viewModel.degradedReason(source.id)
                        Text(
                            when {
                                broken != null -> tr("{0} • Caída ahora mismo, oculta hasta que vuelva: {1}", types, broken)
                                source.isExperimental -> tr("{0} • Experimental: el sitio puede no responder", types)
                                else -> types
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = viewModel.isSourceEnabled(app, source.id),
                            onCheckedChange = { viewModel.setSourceEnabled(source.id, it) }
                        )
                    }
                )
            }

            item {
                ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(tr("Diagnóstico de fuentes")) },
                    supportingContent = { Text(tr("Probar cada fuente y ver si funciona")) },
                    modifier = Modifier.clickable(onClick = onOpenDiagnostics)
                )
            }

            item { GroupTitle(tr("Red")) }
            item {
                SwitchItem(
                    "DNS seguro (DNS sobre HTTPS)",
                    tr("Evita los bloqueos por DNS de algunos operadores usando Cloudflare 1.1.1.1"),
                    network.dnsOverHttps
                ) { v -> viewModel.updateNetwork { it.copy(dnsOverHttps = v) } }
            }
            item {
                Text(
                    tr("Proxy (para sitios bloqueados por IP; p. ej. Orbot/Tor en 127.0.0.1:9050 con SOCKS5)"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                ChipRow {
                    ProxyType.entries.forEach { type ->
                        FilterChip(
                            selected = network.proxyType == type,
                            onClick = { viewModel.updateNetwork { it.copy(proxyType = type) } },
                            label = { Text(type.displayName) }
                        )
                    }
                }
            }
            if (network.proxyType != ProxyType.NONE) {
                item { ProxyFields(network, onSave = { host, port -> viewModel.updateNetwork { it.copy(proxyHost = host, proxyPort = port) } }) }
            }

            item { GroupTitle(tr("Tor")) }
            item {
                Text(
                    tr("Enruta fuentes, descargas, vídeo y el navegador por Tor. Cloudflare suele bloquear las salidas de Tor: ") +
                        tr("desactiva Tor en las fuentes que fallen (lista de abajo) y pruébalas en Diagnóstico de fuentes."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                ChipRow {
                    TorMode.entries.forEach { mode ->
                        FilterChip(
                            selected = network.torMode == mode,
                            onClick = { viewModel.updateNetwork { it.copy(torMode = mode) } },
                            label = { Text(mode.displayName) }
                        )
                    }
                }
            }
            if (network.torMode != TorMode.OFF) {
                item {
                    ListItem(
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = {
                            Text(
                                when (tor.phase) {
                                    TorPhase.CONNECTED -> tr("Conectado a Tor")
                                    TorPhase.STARTING -> tor.connectingLabel
                                    TorPhase.ERROR -> tr("Error de Tor")
                                    TorPhase.DISABLED -> tr("Tor inactivo")
                                }
                            )
                        },
                        supportingContent = tor.error?.let { { Text(it) } }
                    )
                }
                item {
                    SwitchItem(
                        tr("Fuentes, descargas y vídeo por Tor"),
                        tr("Mientras Tor no esté conectado, estas peticiones se bloquean en vez de salir sin protección"),
                        network.torRoutesApp
                    ) { v -> viewModel.updateNetwork { it.copy(torRoutesApp = v) } }
                }
                item {
                    SwitchItem(
                        tr("Navegador por Tor"),
                        tr("El navegador de captura no carga nada hasta que Tor esté conectado"),
                        network.torRoutesBrowser
                    ) { v -> viewModel.updateNetwork { it.copy(torRoutesBrowser = v) } }
                }
                item {
                    Text(tr("Usar Tor en cada fuente"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                items(viewModel.sources, key = { "tor-" + it.id }) { source ->
                    ListItem(
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text(source.name) },
                        supportingContent = { Text(if (source.id in network.torExcludedSources) tr("Conexión directa") else tr("Por Tor")) },
                        trailingContent = {
                            Switch(
                                checked = source.id !in network.torExcludedSources,
                                onCheckedChange = { useTor ->
                                    viewModel.updateNetwork {
                                        it.copy(torExcludedSources = if (useTor) it.torExcludedSources - source.id else it.torExcludedSources + source.id)
                                    }
                                }
                            )
                        }
                    )
                }
            }
            item {
                Text(tr("Página de inicio y buscador del navegador"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                ChipRow {
                    BrowserHome.entries.forEach { home ->
                        FilterChip(
                            selected = network.browserHome == home,
                            onClick = { viewModel.updateNetwork { it.copy(browserHome = home) } },
                            label = { Text(home.displayName) }
                        )
                    }
                }
            }

            item { GroupTitle(tr("Descargas")) }
            item {
                SwitchItem(
                    tr("Solo con Wi-Fi"),
                    tr("Las descargas esperan a una conexión sin tarifa de datos"),
                    app.downloadsWifiOnly
                ) { v -> viewModel.updateApp { it.copy(downloadsWifiOnly = v) } }
            }
            item {
                ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(tr("Descargas simultáneas")) },
                    supportingContent = {
                        ChipRow {
                            (1..4).forEach { count ->
                                FilterChip(
                                    selected = app.maxConcurrentDownloads == count,
                                    onClick = { viewModel.updateApp { it.copy(maxConcurrentDownloads = count) } },
                                    label = { Text("$count") }
                                )
                            }
                        }
                    }
                )
            }
            item {
                ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(tr("Seguridad de las descargas")) },
                    supportingContent = {
                        Text(
                            tr("Solo HTTPS (o .onion por Tor), se comprueba el tipo y tamaño de cada archivo, ") +
                                tr("se guardan en el almacenamiento privado de la app y se reanudan si se interrumpen.")
                        )
                    }
                )
            }

            item {
                SwitchItem(
                    tr("Guardar también en Descargas"),
                    tr("Copia cada capítulo en Download/Yomukai, por tipo y serie, para abrirlo desde cualquier explorador de archivos. Ocupa el doble"),
                    app.exportDownloads
                ) { v -> viewModel.updateApp { it.copy(exportDownloads = v) } }
            }
            if (app.exportDownloads) {
                item {
                    Text(tr("Formato de manga, manhwa y cómic"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    ChipRow {
                        ComicFormat.entries.forEach { format ->
                            FilterChip(
                                selected = app.comicFormat == format,
                                onClick = { viewModel.updateApp { it.copy(comicFormat = format) } },
                                label = { Text(format.displayName) }
                            )
                        }
                    }
                    Text(tr("Formato de novela"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    ChipRow {
                        NovelFormat.entries.forEach { format ->
                            FilterChip(
                                selected = app.novelFormat == format,
                                onClick = { viewModel.updateApp { it.copy(novelFormat = format) } },
                                label = { Text(format.displayName) }
                            )
                        }
                    }
                }
                item {
                    ListItem(
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text(tr("Copiar lo ya descargado")) },
                        supportingContent = { Text(tr("Pasa a Download/Yomukai lo que descargaste antes")) },
                        modifier = Modifier.clickable {
                            Toast.makeText(context, tr("Copiando descargas..."), Toast.LENGTH_SHORT).show()
                            viewModel.exportDownloads { count -> Toast.makeText(context, tr("{0} capítulos revisados", count), Toast.LENGTH_SHORT).show() }
                        }
                    )
                }
            }

            item { GroupTitle(tr("Almacenamiento")) }
            item {
                ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(tr("Limpiar caché de imágenes")) },
                    supportingContent = { Text(tr("En uso: {0}", Formatter.formatShortFileSize(context, storage.first))) },
                    leadingContent = { Icon(Icons.Default.Image, null) },
                    modifier = Modifier.clickable { viewModel.clearImageCache(context) }
                )
            }
            item {
                ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(tr("Capítulos descargados")) },
                    supportingContent = {
                        Text(tr("{0} • Gestiónalos en Biblioteca › Descargas", Formatter.formatShortFileSize(context, storage.second)))
                    },
                    leadingContent = { Icon(Icons.Default.Delete, null) }
                )
            }
            item {
                ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(tr("Borrar historial de lectura")) },
                    leadingContent = { Icon(Icons.Default.History, null) },
                    modifier = Modifier.clickable { confirmHistory = true }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(tr("Yomukai {0}", BuildConfig.VERSION_NAME)) },
                    supportingContent = { Text(tr("Manga, manhwa, novelas ligeras y anime. Sin cuentas ni seguimiento.")) },
                    leadingContent = { Icon(Icons.Default.Info, null) }
                )
            }
        }
    }

    if (confirmHistory) {
        AlertDialog(
            onDismissRequest = { confirmHistory = false },
            title = { Text(tr("¿Borrar historial?")) },
            text = { Text(tr("El progreso por capítulo se conserva; solo se borra la lista de historial.")) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearHistory(); confirmHistory = false }) { Text(tr("Borrar")) }
            },
            dismissButton = { TextButton(onClick = { confirmHistory = false }) { Text(tr("Cancelar")) } }
        )
    }
}

@Composable
private fun GroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

@Composable
private fun SwitchItem(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) }
    )
}

@Composable
private fun ProxyFields(settings: NetworkSettings, onSave: (String, Int) -> Unit) {
    var host by remember(settings.proxyHost) { mutableStateOf(settings.proxyHost) }
    var port by remember(settings.proxyPort) { mutableStateOf(settings.proxyPort.toString()) }
    val portValue = port.toIntOrNull()?.takeIf { it in 1..65535 }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it.trim() },
            label = { Text(tr("Host")) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text(tr("Puerto")) },
            singleLine = true,
            isError = portValue == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(100.dp)
        )
        Spacer(Modifier.width(8.dp))
        InkOutlinedButton(
            onClick = { portValue?.let { onSave(host, it) } },
            enabled = host.isNotBlank() && portValue != null && (host != settings.proxyHost || portValue != settings.proxyPort)
        ) { Text(tr("Guardar")) }
    }
}
