package com.nelson.yomukai.ui.social

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.social.ShareCard
import com.nelson.yomukai.data.sync.ServerSync
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.LocalAppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Direct messages (F4.2), shared cards and the share sheet (F4.3), and the common rooms (F4.1) in the app: the same
// server API as the web's /mensajes, /salas and «Compartir», so phones and computers talk to each other.

private val gson = Gson()
private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
private fun JsonElement?.objs(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()
private fun JsonObject.card(): ShareCard? = get("card")?.takeIf { it.isJsonObject }?.let { runCatching { gson.fromJson(it, ShareCard::class.java) }.getOrNull() }

private val TYPE_NAMES = mapOf("ANIME" to "Anime", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua", "COMIC" to "Cómic", "NOVEL" to "Novela")

/** A shared work, episode or chapter; a spoiler stays covered until tapped. [onOpen] opens it in the app. */
@Composable
fun ShareCardView(card: ShareCard, mine: Boolean, onOpen: ((ShareCard) -> Unit)? = null) {
    var shown by remember { mutableStateOf(false) }
    val covered = card.spoiler && !mine && !shown
    val what = if (card.kind == "work") "" else card.label.ifBlank { card.number?.let { "#" + (if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()) }.orEmpty() }
    Column(
        Modifier.widthIn(max = 320.dp).fillMaxWidth().padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .clickable(enabled = covered || onOpen != null) { if (covered) shown = true else onOpen?.invoke(card) }
            .padding(10.dp)
    ) {
        if (covered) {
            Text(tr("Spoiler") + " · " + card.title + if (what.isNotEmpty()) " · $what" else "", fontWeight = FontWeight.Bold)
            Text(tr("Toca para verlo"), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(card.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (what.isNotEmpty()) Text(what, style = MaterialTheme.typography.bodySmall)
            val tags = listOfNotNull(TYPE_NAMES[card.type]?.let { tr(it) }, card.lang.ifBlank { null }, if (card.spoiler) tr("Spoiler") else null)
            if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---------- Conversations ----------

@Suppress("CyclomaticComplexMethod")
@Composable
internal fun MessagesView(sync: ServerSync, onOpen: (String) -> Unit) {
    var list by remember { mutableStateOf<List<JsonObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        // Kept fresh while the list is open (the live stream is the conversation's job).
        while (true) {
            runCatching { sync.api("GET", "/api/dm") }.onSuccess { list = it.objs(); error = null }.onFailure { error = it.message; if (list == null) list = emptyList() }
            delay(LIST_REFRESH_MS)
        }
    }
    LaunchedEffect(picking) {
        if (picking && people.isEmpty()) people = runCatching { sync.api("GET", "/api/users").objs().filter { it.get("me")?.asBoolean != true }.map { it.str("name") } }.getOrDefault(emptyList())
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Mensajes"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = { picking = !picking }) { Text(tr("Nuevo mensaje")) }
        }
        if (picking) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                people.forEach { name -> AssistChip(onClick = { picking = false; onOpen(name) }, label = { Text(name) }) }
            }
        }
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        val items = list ?: return@Column Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        if (items.isEmpty()) Text(tr("Aún no tienes conversaciones. Pulsa + o escribe a alguien desde su perfil o desde Amigos."), Modifier.padding(16.dp))
        LazyColumn {
            items(items, key = { it.getAsJsonObject("peer")?.get("id")?.asLong ?: 0 }) { c ->
                val peer = c.getAsJsonObject("peer") ?: JsonObject()
                val last = c.getAsJsonObject("last")
                val unread = c.get("unread")?.asInt ?: 0
                val muted = c.get("muted")?.asBoolean == true
                val preview = when {
                    c.get("blocked")?.asBoolean == true -> tr("Bloqueado")
                    last == null -> ""
                    else -> (if (last.get("mine")?.asBoolean == true) tr("Tú: ") else "") + last.str("text").ifBlank { last.card()?.title?.let { "📎 $it" }.orEmpty() }
                }
                ListItem(
                    headlineContent = { Text(peer.str("name") + if (muted) " 🔕" else "", fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal) },
                    supportingContent = { Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { if (unread > 0 && !muted) Badge { Text(if (unread > 9) "9+" else "$unread") } },
                    modifier = Modifier.clickable { onOpen(peer.str("name")) }
                )
            }
        }
    }
}

private data class Line(val id: Long, val mine: Boolean, val text: String, val card: ShareCard?, val at: Long)

private fun JsonObject.line() = Line(get("id")?.asLong ?: 0, get("mine")?.asBoolean == true, str("text"), card(), get("at")?.asLong ?: 0)

/** One conversation, live: new messages, «escribiendo…» and «visto» arrive over the server's stream. */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
internal fun ConversationView(sync: ServerSync, name: String, onOpenCard: (ShareCard) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var lines by remember(name) { mutableStateOf<List<Line>>(emptyList()) }
    var seenUpTo by remember(name) { mutableLongStateOf(0L) }
    var muted by remember(name) { mutableStateOf(false) }
    var blocked by remember(name) { mutableStateOf(false) }
    var typingUntil by remember(name) { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var draft by remember(name) { mutableStateOf("") }
    var error by remember(name) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Line?>(null) }
    var lastTyping by remember { mutableLongStateOf(0L) }
    val list = rememberLazyListState()
    val path = "/api/dm/" + enc(name)

    fun toast(text: String?) = android.widget.Toast.makeText(context, text ?: tr("Error"), android.widget.Toast.LENGTH_SHORT).show()
    suspend fun markRead() {
        val last = lines.lastOrNull { !it.mine } ?: return
        runCatching { sync.api("POST", "$path/read", JsonObject().apply { addProperty("upTo", last.id) }) }
    }
    suspend fun load() {
        runCatching { sync.api("GET", path)?.asJsonObject }.onSuccess { h ->
            h ?: return@onSuccess
            lines = h.getAsJsonArray("messages").objs().map { it.line() }
            seenUpTo = h.get("seenUpTo")?.asLong ?: 0
            muted = h.get("muted")?.asBoolean == true
            blocked = h.get("blocked")?.asBoolean == true
            error = null
            markRead()
        }.onFailure { error = it.message }
    }
    LaunchedEffect(name) { load() }
    LaunchedEffect(name) {
        sync.liveEvents().collect { (event, data) ->
            val d = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return@collect
            if (!d.str("peer").equals(name, ignoreCase = true)) return@collect
            when (event) {
                "dm" -> d.getAsJsonObject("message")?.line()?.let { l ->
                    if (lines.none { it.id == l.id }) lines = lines + l
                    if (!l.mine) { typingUntil = 0; markRead() }
                }
                "dm_typing" -> typingUntil = System.currentTimeMillis() + TYPING_SHOWN_MS
                "dm_seen" -> seenUpTo = maxOf(seenUpTo, d.get("upTo")?.asLong ?: 0)
                "dm_deleted" -> lines = lines.filter { it.id != d.get("id")?.asLong }
            }
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        scope.launch {
            runCatching { sync.api("POST", path, JsonObject().apply { addProperty("text", text) })?.asJsonObject }
                .onSuccess { m -> draft = ""; m?.line()?.let { l -> if (lines.none { it.id == l.id }) lines = lines + l } }
                .onFailure { toast(it.message) }
        }
    }
    fun flag(kind: String, on: Boolean) = scope.launch {
        runCatching { sync.api("POST", "$path/$kind", JsonObject().apply { addProperty("on", on) }) }
            .onSuccess { if (kind == "mute") muted = on else blocked = on }.onFailure { toast(it.message) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (typingUntil > now) Text(tr("escribiendo..."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = { flag("mute", !muted) }) { Text(if (muted) tr("Quitar silencio") else tr("Silenciar")) }
            TextButton(onClick = { flag("block", !blocked) }) { Text(if (blocked) tr("Desbloquear") else tr("Bloquear")) }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(lines, key = { it.id }) { m ->
                val lastMine = lines.lastOrNull { it.mine }?.id == m.id
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Column(
                        Modifier.widthIn(max = 300.dp)
                            .background(if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                            .combinedClickable(onClick = {}, onLongClick = { if (m.mine) deleting = m })
                            .padding(10.dp)
                    ) {
                        m.card?.let { ShareCardView(it, m.mine, onOpenCard) }
                        if (m.text.isNotEmpty()) Text(m.text)
                        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(m.at))
                        Text(time + if (m.mine && lastMine && seenUpTo >= m.id) " · " + tr("Visto") else "", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (blocked) Text(tr("Has bloqueado a esta persona. Desbloquéala para escribirle."), Modifier.padding(16.dp))
        else Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                draft, { v ->
                    draft = v.take(MAX_DM)
                    if (System.currentTimeMillis() - lastTyping > TYPING_EVERY_MS && draft.isNotBlank()) {
                        lastTyping = System.currentTimeMillis()
                        scope.launch { runCatching { sync.api("POST", "$path/typing", JsonObject()) } }
                    }
                },
                Modifier.weight(1f), placeholder = { Text(tr("Escribe un mensaje")) }, maxLines = 4
            )
            IconButton(enabled = draft.isNotBlank(), onClick = ::send) { Icon(Icons.AutoMirrored.Filled.Send, tr("Enviar")) }
        }
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Borrar este mensaje")) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { runCatching { sync.api("DELETE", "/api/dm/messages/${m.id}") }.onSuccess { lines = lines.filter { it.id != m.id } }.onFailure { toast(it.message) } }
                }) { Text(tr("Borrar")) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancelar")) } }
        )
    }
}

// ---------- Common rooms ----------

@Composable
internal fun OpenRoomsView(sync: ServerSync, onRoom: (String) -> Unit) {
    var rooms by remember { mutableStateOf<List<JsonObject>?>(null) }
    var name by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            rooms = runCatching { sync.api("GET", "/api/rooms/open").objs() }.getOrDefault(rooms ?: emptyList())
            delay(LIST_REFRESH_MS)
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                tr("Salas abiertas a todo el grupo: entra, mira la cola de episodios (de varios animes) y vota para saltar. Cuando acaba un episodio empieza el siguiente de la cola solo."),
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it.take(60) }, Modifier.weight(1f), singleLine = true, placeholder = { Text(tr("Nombre de la sala")) })
                TextButton(onClick = {
                    scope.launch {
                        runCatching { sync.api("POST", "/api/rooms", JsonObject().apply { addProperty("name", name.trim()); addProperty("open", true) })?.asJsonObject }
                            .onSuccess { r -> r?.str("code")?.let(onRoom) }
                            .onFailure { android.widget.Toast.makeText(context, it.message, android.widget.Toast.LENGTH_LONG).show() }
                    }
                }) { Text(tr("Crear sala común")) }
            }
        }
        val list = rooms
        if (list == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else if (list.isEmpty()) item { Text(tr("Aún no hay salas comunes. Crea una y pásale el enlace al grupo."), Modifier.padding(16.dp)) }
        else items(list, key = { it.str("code") }) { r ->
            val members = r.getAsJsonArray("members")?.map { it.asString }.orEmpty()
            val now = listOf(r.str("title"), r.str("episode")).filter { it.isNotBlank() }.joinToString(" · ")
            val next = r.getAsJsonObject("next")?.let { n -> listOf(n.str("title"), n.str("episode")).filter { it.isNotBlank() }.joinToString(" · ") }
            ListItem(
                headlineContent = { Text(r.str("name"), fontWeight = FontWeight.Bold) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            now.ifBlank { tr("No se está viendo nada") },
                            next?.let { tr("Después: {0}", it) },
                            if (members.isEmpty()) tr("Vacía") else tr("{0} dentro: {1}", members.size, members.joinToString(", ")),
                            tr("{0} en cola", r.get("queued")?.asInt ?: 0)
                        ).joinToString("\n")
                    )
                },
                trailingContent = { TextButton(onClick = { onRoom(r.str("code")) }) { Text(tr("Entrar")) } },
                modifier = Modifier.clickable { onRoom(r.str("code")) }
            )
        }
    }
}

// ---------- Share sheet ----------

/** Compartir (F4.3): a work, episode or chapter to a person, a room or the work's forum thread, in two taps. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun ShareSheet(card: ShareCard, onDismiss: () -> Unit) {
    val sync = LocalAppContainer.current.serverSync
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var note by remember { mutableStateOf("") }
    var shield by remember { mutableStateOf(true) }
    var people by remember { mutableStateOf<List<String>?>(null) }
    var rooms by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var busy by remember { mutableStateOf(false) }
    val canShield = card.kind != "work" && card.number != null
    val final = card.copy(spoiler = canShield && shield)
    LaunchedEffect(Unit) {
        val convos = runCatching { sync.api("GET", "/api/dm").objs().filter { it.get("blocked")?.asBoolean != true }.map { it.getAsJsonObject("peer").str("name") } }.getOrDefault(emptyList())
        val users = runCatching { sync.api("GET", "/api/users").objs().filter { it.get("me")?.asBoolean != true }.map { it.str("name") } }.getOrDefault(emptyList())
        people = (convos + users).distinct()
        val mine = runCatching { sync.api("GET", "/api/rooms/mine")?.asJsonObject?.str("code") }.getOrNull()
        val others = runCatching { sync.api("GET", "/api/rooms").objs() + sync.api("GET", "/api/rooms/open").objs() }.getOrDefault(emptyList())
        rooms = (listOfNotNull(mine?.let { it to tr("Mi sala") }) + others.map { it.str("code") to it.str("name") }).distinctBy { it.first }
    }
    fun body() = JsonObject().apply { addProperty("text", note.trim()); add("card", gson.toJsonTree(final)) }
    fun run(done: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { action() }
                .onSuccess { android.widget.Toast.makeText(context, done, android.widget.Toast.LENGTH_SHORT).show(); onDismiss() }
                .onFailure { android.widget.Toast.makeText(context, it.message, android.widget.Toast.LENGTH_LONG).show() }
            busy = false
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Compartir"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ShareCardView(final, mine = true)
            OutlinedTextField(note, { note = it.take(MAX_NOTE) }, Modifier.fillMaxWidth(), placeholder = { Text(tr("Añade un mensaje (opcional)")) }, singleLine = true)
            if (canShield) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(shield, { shield = it })
                Text(tr("Escudo anti-spoilers: se tapa hasta que lo toquen"), style = MaterialTheme.typography.bodySmall)
            }
            Text(tr("Mensaje directo"), fontWeight = FontWeight.Bold)
            val list = people
            if (list == null) CircularProgressIndicator(Modifier.size(24.dp))
            else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (list.isEmpty()) Text(tr("No hay nadie más en el servidor todavía."))
                list.forEach { n -> AssistChip(enabled = !busy, onClick = { run(tr("Enviado a {0}", n)) { sync.api("POST", "/api/dm/" + enc(n), body()) } }, label = { Text(n) }) }
            }
            if (rooms.isNotEmpty()) {
                Text(tr("Sala"), fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rooms.forEach { (code, label) ->
                        AssistChip(enabled = !busy, onClick = { run(tr("Compartido en {0}", label)) { sync.api("POST", "/api/rooms/$code/chat", body()) } }, label = { Text(label) })
                    }
                }
            }
            if (card.sourceId.isNotEmpty()) {
                Text(tr("Foro"), fontWeight = FontWeight.Bold)
                AssistChip(enabled = !busy, onClick = {
                    run(tr("Publicado en el hilo de {0}", card.title)) {
                        val w = sync.api("GET", "/api/forum/work?s=" + enc(card.sourceId) + "&id=" + enc(card.mediaId))?.asJsonObject
                        val thread = w?.get("thread")?.takeIf { it.isJsonObject }?.asJsonObject?.get("id")?.asLong
                        val post = JsonObject().apply {
                            addProperty("body", note.trim()); add("card", gson.toJsonTree(final))
                            if (final.spoiler) addProperty("spoilerUntil", final.number)
                        }
                        if (thread != null) sync.api("POST", "/api/forum/$thread/posts", post)
                        else sync.api("POST", "/api/forum", post.apply {
                            addProperty("category", "work"); addProperty("sourceId", card.sourceId); addProperty("mediaId", card.mediaId); addProperty("title", card.title)
                        })
                    }
                }, label = { Text(tr("Hilo de {0}", card.title)) })
            }
        }
    }
}

private const val LIST_REFRESH_MS = 15_000L
private const val TYPING_SHOWN_MS = 5_000L
private const val TYPING_EVERY_MS = 3_000L
private const val MAX_DM = 2_000
private const val MAX_NOTE = 1_000
