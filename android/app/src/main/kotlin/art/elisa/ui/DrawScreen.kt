package art.elisa.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButtonDefaults
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import art.elisa.Speech
import art.elisa.Store
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import art.elisa.Api
import art.elisa.AppVersion
import art.elisa.BuildConfig
import art.elisa.Drawing
import art.elisa.Photos
import art.elisa.R
import art.elisa.Turn
import art.elisa.Whoami
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** What was just sent and is still being drawn; shown as a bubble until the reply lands. */
private data class Pending(val prompt: String, val photos: List<Uri>)

/**
 * The whole app after pairing. A drawing is a conversation: each request is a
 * bubble on the right with its words and photos, each picture a reply on the
 * left, and "Change it" continues the same thread. Scrolling up is the history.
 */
@Composable
fun DrawScreen(api: Api, store: Store, onForget: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gallery = remember { mutableStateListOf<Drawing>() }
    var current by remember { mutableStateOf<Drawing?>(null) }
    var prompt by remember { mutableStateOf("") }
    val photos = remember { mutableStateListOf<Uri>() }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showGallery by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppVersion?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        runCatching { gallery.addAll(api.drawings()) }
        // Reopen where we left off: the newest drawing, so "Change it" still works.
        if (current == null) current = gallery.firstOrNull { it.images.isNotEmpty() }
        // Sideloaded apps never update themselves; this is the whole update story.
        runCatching { api.appVersion() }.getOrNull()
            ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            ?.let { update = it }
    }

    // The request shows up as a bubble at once; the photos are shrunk while the
    // spinner is already on screen, where a wait is expected anyway.
    fun run(fresh: Boolean, block: suspend (List<ByteArray>) -> Drawing) {
        if (busy) return
        busy = true; error = null
        // Like any chat: the composer empties on send, and refills if the send fails.
        val sent = Pending(prompt, photos.toList())
        pending = sent
        prompt = ""
        photos.clear()
        val before = current
        if (fresh) current = null
        scope.launch {
            try {
                val d = block(sent.photos.map { Photos.shrink(ctx, it) })
                current = d
                gallery.removeAll { it.id == d.id }
                gallery.add(0, d)
            } catch (e: Exception) {
                current = before
                prompt = sent.prompt
                photos.addAll(sent.photos)
                error = e.message ?: "Something went wrong."
            } finally {
                pending = null
                busy = false
            }
        }
    }

    if (showSettings) {
        SettingsScreen(api, store, onBack = { showSettings = false }, onForget = onForget)
        return
    }
    if (showGallery) {
        GalleryScreen(api, gallery, onPick = { current = it; showGallery = false }, onBack = { showGallery = false })
        return
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🎨 Elisa Art", style = MaterialTheme.typography.headlineSmall)
            Row {
                IconButton(onClick = { showGallery = true }) { Icon(Icons.Filled.Collections, "Gallery") }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") }
            }
        }

        val d = current
        val p = pending
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            update?.let { v -> item { UpdateBanner(v) { update = null } } }
            if (d == null && p == null) sampleSession()
            d?.turns?.forEach { t ->
                if (t.prompt.isNotBlank() || t.photos.isNotEmpty()) {
                    item { RequestBubble(t.prompt, t.photos.map { api.authed(api.photoUrl(d, it), ctx) }) }
                }
                item { ReplyBubble(api, d, t) }
            }
            p?.let {
                item { RequestBubble(it.prompt, it.photos) }
                item { ThinkingBubble(withPhotos = it.photos.isNotEmpty()) }
            }
        }
        // Keep the newest bubble in view, like any chat. The sample session is
        // read top-down, so it stays where it starts.
        LaunchedEffect(d?.turns?.size, p, busy) {
            val n = listState.layoutInfo.totalItemsCount
            if (n > 0 && (d != null || p != null)) listState.animateScrollToItem(n - 1)
        }

        // Above the composer, not inside the thread: it must be visible even when
        // the list is scrolled elsewhere, and it goes away on the next attempt.
        error?.let { e ->
            Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        PhotoStrip(photos, enabled = !busy)
        Spacer(Modifier.height(8.dp))
        var listening by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = {
                    Text(
                        when {
                            // Example sentences in the kid's own words, so the hint is something she could say.
                            listening -> "Listening…"
                            photos.isNotEmpty() -> "این رو بذار روی دستم و صورتیش کن"
                            d == null && p == null -> "یه اژدهای بنفش که بستنی می‌خوره"
                            else -> "آسمونش رو صورتی کن"
                        },
                    )
                },
                modifier = Modifier.weight(1f),
                minLines = 2,
                maxLines = 4,
                enabled = !busy,
            )
            Spacer(Modifier.width(8.dp))
            MicButton(
                enabled = !busy,
                language = store.speechLanguage,
                onListening = { listening = it },
                // Spoken words continue whatever is already typed.
                onText = { spoken, base -> prompt = listOf(base.trimEnd(), spoken).filter { it.isNotBlank() }.joinToString(" ") },
                baseText = { prompt },
                onError = { error = it.ifBlank { null } },
            )
        }
        Spacer(Modifier.height(8.dp))
        // A photo alone is a request too ("draw this"), so photos unlock the button like words do.
        val ready = !busy && (prompt.isNotBlank() || photos.isNotEmpty())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d != null) {
                Button(
                    onClick = { run(fresh = false) { api.continueDrawing(d.id, prompt, it) } },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Filled.Brush, null); Spacer(Modifier.size(6.dp)); Text("Change it") }
                OutlinedButton(
                    onClick = { run(fresh = true) { api.newDrawing(prompt, it) } },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) { Text("New drawing") }
            } else {
                Button(
                    onClick = { run(fresh = true) { api.newDrawing(prompt, it) } },
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Filled.Brush, null); Spacer(Modifier.size(6.dp)); Text("Draw it!") }
            }
        }
    }
}

/**
 * Tap to talk, like a chat app: the button pulses while listening, words land
 * in the text box as they are heard, and a pause or a second tap ends it. The
 * microphone permission is asked on the first tap and never again.
 */
@Composable
private fun MicButton(
    enabled: Boolean,
    language: String,
    onListening: (Boolean) -> Unit,
    onText: (spoken: String, base: String) -> Unit,
    baseText: () -> String,
    onError: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val speech = remember { Speech(ctx) }
    var listening by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { speech.stop() } }

    fun begin() {
        val base = baseText()
        onError("")
        listening = true; onListening(true)
        speech.start(
            language,
            onText = { onText(it, base) },
            onDone = { err -> listening = false; onListening(false); err?.let(onError) },
        )
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else onError("The app needs the microphone to hear you.")
    }

    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        1f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse",
    )
    FilledIconButton(
        onClick = {
            when {
                listening -> { speech.stop(); listening = false; onListening(false) }
                !speech.available -> onError("Voice typing isn't available on this phone.")
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> begin()
                else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        enabled = enabled,
        modifier = Modifier.size(56.dp).scale(if (listening) pulse else 1f),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) "Stop listening" else "Say it", Modifier.size(28.dp))
    }
}

/** The person's side of the conversation: photos first, then the words, on the right. */
@Composable
private fun RequestBubble(text: String, photos: List<Any>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.End) {
                if (photos.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        photos.forEach { m ->
                            AsyncImage(m, "Attached photo", Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        }
                    }
                    if (text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ReplyBubble(api: Api, d: Drawing, t: Turn) {
    val ctx = LocalContext.current
    ReplyBubble(t.images.map { api.authed(api.imageUrl(d, it), ctx) }, t.text)
}

/** The picture(s) that came back and the one-line reply, on the left. */
@Composable
private fun ReplyBubble(images: List<Any>, text: String) {
    Column(Modifier.fillMaxWidth(0.92f)) {
        images.forEach { m ->
            AsyncImage(
                m, null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.height(6.dp))
        }
        val line = text.ifBlank { if (images.isEmpty()) "Hmm, nothing came out that time. Try again?" else "" }
        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun ThinkingBubble(withPhotos: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(if (withPhotos) "Looking at your photos and drawing… about a minute" else "Drawing… this takes about a minute")
        }
    }
}

/**
 * Who am I, how many drawings are left, which build. "Forget code" lives only
 * here, behind a confirmation: a stray tap on the main screen must never log a
 * kid out. Drawings stay on the server either way.
 */
@Composable
private fun SettingsScreen(api: Api, store: Store, onBack: () -> Unit, onForget: () -> Unit) {
    var who by remember { mutableStateOf<Whoami?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var speechLanguage by remember { mutableStateOf(store.speechLanguage) }
    LaunchedEffect(Unit) { who = runCatching { api.whoami() }.getOrNull() }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        ListItem(headlineContent = { Text("Signed in as") }, supportingContent = { Text(who?.name ?: "…") })
        ListItem(
            headlineContent = { Text("Drawings left this hour") },
            supportingContent = { Text(who?.drawings_left_this_hour?.toString() ?: "…") },
        )
        ListItem(headlineContent = { Text("App version") }, supportingContent = { Text(BuildConfig.VERSION_NAME) })
        ListItem(
            headlineContent = { Text("Voice language") },
            supportingContent = {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Speech.LANGUAGES.forEach { (tag, label) ->
                        FilterChip(
                            selected = speechLanguage == tag,
                            onClick = { speechLanguage = tag; store.speechLanguage = tag },
                            label = { Text(label) },
                        )
                    }
                }
            },
        )
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Forget code on this device") }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Forget this code?") },
            text = { Text("You'll need to type the code again to draw. Your drawings are kept.") },
            confirmButton = { TextButton(onClick = { confirm = false; onForget() }) { Text("Forget") } },
            dismissButton = { Button(onClick = { confirm = false }) { Text("Keep drawing") } },
        )
    }
}

/** Opens the APK link in the browser; Android's installer takes over from there. */
@Composable
private fun UpdateBanner(v: AppVersion, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("New version ${v.versionName} is out!", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("Later") }
            Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.url))) }) { Text("Update") }
        }
    }
}

/**
 * The first thing a new person sees: a whole session, played back in the same
 * bubbles a real one uses. Two photos and a sentence, the picture that came
 * back, a follow-up, its picture. It teaches everything at once: photos are
 * a thing, you talk in your own words, and you can keep changing the picture.
 * No captions around it: a chat needs none.
 */
private object Example {
    class Sample(val prompt: String, val photos: List<String>, val result: Int, val reply: String)

    // Spoken Persian, the way the kid would say it out loud; it also shows that any language works.
    val turns = listOf(
        Sample(
            "این دستبند رو بذار روی دستم و صورتیش کن",
            listOf("example_wristband", "example_hand"),
            R.drawable.example_result,
            "دستبندت رو صورتی کردم و روی مچ دستت گذاشتم! 🩷",
        ),
        Sample(
            "حالا روی هر مهره یه ستاره‌ی زرد کوچولو بذار",
            emptyList(),
            R.drawable.example_result2,
            "حالا هر مهره یه ستاره‌ی زرد کوچولو داره! ⭐",
        ),
    )
}

private fun LazyListScope.sampleSession() {
    Example.turns.forEach { t ->
        item {
            val ctx = LocalContext.current
            RequestBubble(t.prompt, t.photos.map { Uri.parse("android.resource://${ctx.packageName}/raw/$it") })
        }
        item { ReplyBubble(listOf(t.result), t.reply) }
    }
}

/**
 * Attached photos as thumbnails, each with a remove badge, followed by the two
 * ways to add one. The chips stay visible with words on them, not just icons:
 * that is how a kid finds out photos are a thing at all.
 */
@Composable
private fun PhotoStrip(photos: MutableList<Uri>, enabled: Boolean) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Photos.MAX)) { uris ->
        photos.addAll(uris.take(Photos.MAX - photos.size))
    }
    // The camera writes into a URI we hand it; remember which one so the result can be matched.
    var shot by remember { mutableStateOf<Uri?>(null) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) shot?.let { photos.add(it) }
    }
    val canAdd = enabled && photos.size < Photos.MAX

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        photos.forEach { uri ->
            Box(Modifier.size(56.dp)) {
                AsyncImage(
                    model = uri,
                    contentDescription = "Attached photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                )
                Icon(
                    Icons.Filled.Close, "Remove photo",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp).clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.primary).clickable(enabled) { photos.remove(uri) },
                )
            }
        }
        AssistChip(
            onClick = { shot = Photos.newShot(ctx).also { take.launch(it) } },
            enabled = canAdd,
            label = { Text("Take a photo") },
            leadingIcon = { Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp)) },
        )
        AssistChip(
            onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = canAdd,
            label = { Text("Add a photo") },
            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun GalleryScreen(api: Api, gallery: List<Drawing>, onPick: (Drawing) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
            Text("My drawings", style = MaterialTheme.typography.headlineSmall)
        }
        if (gallery.none { it.images.isNotEmpty() }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing here yet!") }
            return
        }
        LazyVerticalGrid(GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(gallery.filter { it.images.isNotEmpty() }, key = { it.id }) { d ->
                Picture(
                    api, d, d.images.last(),
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).clickable { onPick(d) },
                )
            }
        }
    }
}

/** Images need the code as a header too, so Coil gets an authenticated request. */
@Composable
private fun Picture(api: Api, d: Drawing, name: String, modifier: Modifier) {
    val ctx = LocalContext.current
    AsyncImage(
        model = api.authed(api.imageUrl(d, name), ctx),
        contentDescription = d.text,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
