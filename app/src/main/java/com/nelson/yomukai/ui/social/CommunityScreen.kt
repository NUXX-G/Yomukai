package com.nelson.yomukai.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nelson.yomukai.data.sync.ServerSync
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.LocalAppContainer
import kotlinx.coroutines.launch

/**
 * Comunidad (needs the server, like on the web): friends' activity, the people on the server and their profiles, the
 * forum, and your own profile. Same API as the web's /amigos, /u/nombre and /foro pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(onBack: () -> Unit, onOpenWork: (sourceId: String, mediaId: String) -> Unit) {
    val sync = LocalAppContainer.current.serverSync
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var person by rememberSaveable { mutableStateOf<String?>(null) }
    var thread by rememberSaveable { mutableStateOf<Long?>(null) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Comunidad")) },
            navigationIcon = {
                IconButton(onClick = { if (person != null) person = null else if (thread != null) thread = null else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Volver"))
                }
            }
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (sync.serverUrl == null) {
                Text(tr("Conecta la app con tu servidor (Ajustes › Sincronizar) para ver a tus amigos y el foro."), Modifier.padding(16.dp))
                return@Column
            }
            when {
                person != null -> ProfileView(sync, person!!, onOpenWork)
                thread != null -> ThreadView(sync, thread!!)
                else -> {
                    TabRow(selectedTabIndex = tab) {
                        listOf(tr("Actividad"), tr("Amigos"), tr("Foro"), tr("Mi perfil")).forEachIndexed { i, label ->
                            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1) })
                        }
                    }
                    when (tab) {
                        0 -> FeedView(sync, onOpenWork, onPerson = { person = it })
                        1 -> PeopleView(sync) { person = it }
                        2 -> ForumView(sync) { thread = it }
                        else -> MyProfileView(sync) { person = it }
                    }
                }
            }
        }
    }
}

/** Loads [path] from the server once (again when [key] changes); null while loading, the error text on failure. */
@Composable
private fun rememberApi(sync: ServerSync, path: String, key: Any = path): Pair<JsonElement?, String?> {
    var result by remember(key) { mutableStateOf<Pair<JsonElement?, String?>>(null to null) }
    LaunchedEffect(key) {
        result = runCatching { sync.api("GET", path) }.fold({ it to null }, { null to (it.message ?: tr("Error")) })
    }
    return result
}

@Composable
private fun Loading(error: String?) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
    }
}

private fun JsonObject.s(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
private fun JsonObject.i(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
private fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()

private fun ago(at: Long): String {
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> tr("ahora")
        minutes < 60 -> tr("hace {0} min", minutes)
        minutes < 60 * 24 -> tr("hace {0} h", minutes / 60)
        else -> tr("hace {0} d", minutes / (60 * 24))
    }
}

@Composable
private fun FeedView(sync: ServerSync, onOpenWork: (String, String) -> Unit, onPerson: (String) -> Unit) {
    val (data, error) = rememberApi(sync, "/api/feed")
    if (data == null) return Loading(error)
    val entries = data.objects()
    if (entries.isEmpty()) return Text(tr("Todavía no hay actividad de tus amigos."), Modifier.padding(16.dp))
    LazyColumn {
        items(entries) { e ->
            val what = when (e.s("kind")) {
                "episode" -> if (e.i("count") > 1) tr("vio {0} episodios de {1}", e.i("count"), e.s("title")) else tr("vio {0} de {1}", e.s("last"), e.s("title"))
                "chapter" -> if (e.i("count") > 1) tr("leyó {0} capítulos de {1}", e.i("count"), e.s("title")) else tr("leyó {0} de {1}", e.s("last"), e.s("title"))
                "rated" -> tr("valoró {0}", e.s("title"))
                "achievement" -> tr("consiguió el logro «{0}»", e.s("title"))
                else -> e.s("title")
            }
            ListItem(
                headlineContent = { Text(e.s("user"), fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onPerson(e.s("user")) }) },
                supportingContent = { Text(what + if (e.get("marathon")?.asBoolean == true) " · " + tr("¡maratón!") else "") },
                trailingContent = { Text(ago(e.get("at")?.asLong ?: 0), style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.clickable(enabled = e.s("sourceId").isNotEmpty()) { onOpenWork(e.s("sourceId"), e.s("mediaId")) }
            )
        }
    }
}

@Composable
private fun PeopleView(sync: ServerSync, onPerson: (String) -> Unit) {
    val (data, error) = rememberApi(sync, "/api/users")
    if (data == null) return Loading(error)
    LazyColumn {
        items(data.objects().filter { it.get("me")?.asBoolean != true }) { u ->
            ListItem(headlineContent = { Text(u.s("name")) }, modifier = Modifier.clickable { onPerson(u.s("name")) })
        }
    }
}

@Composable
private fun ProfileView(sync: ServerSync, name: String, onOpenWork: (String, String) -> Unit) {
    val (data, error) = rememberApi(sync, "/api/users/" + java.net.URLEncoder.encode(name, Charsets.UTF_8))
    val root = data as? JsonObject ?: return Loading(error)
    val profile = root.getAsJsonObject("profile") ?: JsonObject()
    val stats = root.getAsJsonObject("stats") ?: JsonObject()
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(profile.s("name"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (profile.s("shownTitle").isNotBlank()) Text(profile.s("shownTitle"), color = MaterialTheme.colorScheme.primary)
            if (profile.s("bio").isNotBlank()) Text(profile.s("bio"))
            Text(
                tr("{0} episodios · {1} capítulos · {2} h · racha de {3} días", stats.i("episodes"), stats.i("chapters"), stats.i("hours"), stats.i("streak")),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val achievements = root.get("achievements").objects().filter { it.get("unlocked")?.asBoolean == true || it.get("at")?.isJsonNull == false }
        if (achievements.isNotEmpty()) {
            item { Text(tr("Logros"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Text(achievements.joinToString("  ") { it.s("emoji") + " " + it.s("name") }) }
        }
        val library = root.get("library").objects()
        item {
            Text(tr("Biblioteca"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (root.get("libraryHidden")?.asBoolean == true) Text(tr("Su biblioteca es privada."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (library.isEmpty()) Text(tr("Aún no tiene nada en su biblioteca."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(library) { w ->
            ListItem(
                headlineContent = { Text(w.s("title")) },
                supportingContent = { Text(w.s("readingStatus").ifBlank { w.s("type") }) },
                modifier = Modifier.clickable { onOpenWork(w.s("sourceId"), w.s("mediaId")) }
            )
        }
    }
}

@Composable
private fun MyProfileView(sync: ServerSync, onPerson: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val (data, error) = rememberApi(sync, "/api/profile")
    val me = data as? JsonObject ?: return Loading(error)
    var bio by remember(me) { mutableStateOf(me.s("bio")) }
    var saved by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(me.s("name"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(bio, { bio = it.take(MAX_BIO) }, Modifier.fillMaxWidth(), label = { Text(tr("Sobre mí")) }, minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    saved = runCatching { sync.api("PUT", "/api/profile", JsonObject().apply { addProperty("bio", bio) }); tr("Guardado") }.getOrElse { it.message }
                }
            }) { Text(tr("Guardar")) }
            TextButton(onClick = { onPerson(me.s("name")) }) { Text(tr("Ver mi perfil como lo ven los demás")) }
        }
        saved?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text(tr("Avatar, portada, privacidad y logros a mostrar se cambian desde la web."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ForumView(sync: ServerSync, onThread: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val (cats, catError) = rememberApi(sync, "/api/forum/categories")
    val categories = (cats as? JsonObject)?.entrySet()?.filter { it.key != "work" }?.map { it.key to it.value.asString }.orEmpty()
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val current = category ?: categories.firstOrNull()?.first
    var reload by remember { mutableIntStateOf(0) }
    var writing by remember { mutableStateOf(false) }
    if (cats == null) return Loading(catError)
    Box(Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.forEach { (key, label) -> FilterChip(selected = key == current, onClick = { category = key }, label = { Text(tr(label)) }) }
            }
            if (current != null) {
                val (list, error) = rememberApi(sync, "/api/forum?c=$current&page=0", key = "$current|$reload")
                if (list == null) Loading(error) else {
                    val threads = list.objects()
                    if (threads.isEmpty()) Text(tr("Aún no hay hilos aquí. ¡Abre el primero!"), Modifier.padding(16.dp))
                    LazyColumn {
                        items(threads) { t ->
                            ListItem(
                                headlineContent = { Text((if (t.get("pinned")?.asBoolean == true) "📌 " else "") + t.s("title"), fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text(tr("{0} · {1} mensajes · {2}", t.s("author"), t.i("posts"), ago(t.get("lastPostAt")?.asLong ?: 0))) },
                                modifier = Modifier.clickable { onThread(t.get("id").asLong) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        if (current != null) {
            FloatingActionButton(onClick = { writing = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Default.Add, tr("Nuevo hilo")) }
        }
    }
    if (writing && current != null) {
        var title by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { writing = false },
            title = { Text(tr("Nuevo hilo")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it.take(MAX_TITLE) }, label = { Text(tr("Título")) }, singleLine = true)
                    OutlinedTextField(body, { body = it.take(MAX_POST) }, label = { Text(tr("Mensaje")) }, minLines = 3)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank() && body.isNotBlank(), onClick = {
                    scope.launch {
                        val payload = JsonObject().apply { addProperty("category", current); addProperty("title", title.trim()); addProperty("body", body.trim()) }
                        runCatching { sync.api("POST", "/api/forum", payload) }
                            .onSuccess { r -> writing = false; reload++; (r as? JsonObject)?.get("id")?.asLong?.let(onThread) }
                            .onFailure { error = it.message }
                    }
                }) { Text(tr("Publicar")) }
            },
            dismissButton = { TextButton(onClick = { writing = false }) { Text(tr("Cancelar")) } }
        )
    }
}

@Composable
private fun ThreadView(sync: ServerSync, id: Long) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val (data, error) = rememberApi(sync, "/api/forum/$id?page=0", key = "$id|$reload")
    val root = data as? JsonObject ?: return Loading(error)
    val thread = root.getAsJsonObject("thread") ?: JsonObject()
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(thread.s("title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(root.get("posts").objects()) { p ->
                Column {
                    Text(p.s("author") + " · " + ago(p.get("createdAt")?.asLong ?: 0), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (p.get("hidden")?.asBoolean == true) Text(tr("Mensaje oculto por moderación"), fontStyle = FontStyle.Italic)
                    else Text(markdown(p.s("body")))
                }
            }
        }
        if (thread.get("locked")?.asBoolean == true) {
            Text(tr("Hilo cerrado"), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            sendError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it.take(MAX_POST) }, Modifier.weight(1f), placeholder = { Text(tr("Responder")) })
                IconButton(enabled = draft.isNotBlank(), onClick = {
                    scope.launch {
                        runCatching { sync.api("POST", "/api/forum/$id/posts", JsonObject().apply { addProperty("body", draft.trim()) }) }
                            .onSuccess { draft = ""; sendError = null; reload++ }
                            .onFailure { sendError = it.message }
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, tr("Enviar")) }
            }
        }
    }
}

/**
 * The forum's small markdown as styled text (never HTML): **bold**, *italic*, `code` and "> " quotes; links stay as
 * their text. The same subset the web reads.
 */
internal fun markdown(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { n, raw ->
        if (n > 0) append('\n')
        val quote = raw.startsWith("> ")
        val line = if (quote) raw.removePrefix("> ") else raw
        if (quote) pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
        var i = 0
        while (i < line.length) {
            val rest = line.substring(i)
            val (marker, style) = when {
                rest.startsWith("**") -> "**" to SpanStyle(fontWeight = FontWeight.Bold)
                rest.startsWith("`") -> "`" to SpanStyle(fontFamily = FontFamily.Monospace)
                rest.startsWith("*") -> "*" to SpanStyle(fontStyle = FontStyle.Italic)
                else -> null to null
            }
            val end = marker?.let { line.indexOf(it, i + it.length) } ?: -1
            if (marker != null && style != null && end > i + marker.length) {
                withStyle(style) { append(line.substring(i + marker.length, end)) }
                i = end + marker.length
            } else {
                append(line[i])
                i++
            }
        }
        if (quote) pop()
    }
}

private const val MAX_BIO = 500
private const val MAX_TITLE = 120
private const val MAX_POST = 10_000
