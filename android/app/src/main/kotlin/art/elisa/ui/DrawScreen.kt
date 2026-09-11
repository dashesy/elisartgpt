package art.elisa.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
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
fun DrawScreen(api: Api, onForget: () -> Unit) {
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
        SettingsScreen(api, onBack = { showSettings = false }, onForget = onForget)
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
            if (d == null && p == null) {
                item {
                    Box(Modifier.fillParentMaxHeight(0.92f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Example {
                            photos.clear()
                            photos.addAll(Example.photos(ctx))
                            prompt = Example.PROMPT
                        }
                    }
                }
            }
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
            error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
        }
        // Keep the newest bubble in view, like any chat.
        LaunchedEffect(d?.turns?.size, p, error, busy) {
            val n = listState.layoutInfo.totalItemsCount
            if (n > 0) listState.animateScrollToItem(n - 1)
        }

        Spacer(Modifier.height(8.dp))
        PhotoStrip(photos, enabled = !busy)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = {
                Text(
                    when {
                        photos.isNotEmpty() -> "Put this on my hand and make it pink"
                        d == null && p == null -> "A purple dragon eating ice cream"
                        else -> "Change it… make the sky pink"
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            enabled = !busy,
        )
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

/** The picture(s) that came back and the one-line reply, on the left. */
@Composable
private fun ReplyBubble(api: Api, d: Drawing, t: Turn) {
    Column(Modifier.fillMaxWidth(0.92f)) {
        t.images.forEach { name ->
            Picture(api, d, name, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)))
            Spacer(Modifier.height(6.dp))
        }
        val line = t.text.ifBlank { if (t.images.isEmpty()) "Hmm, nothing came out that time. Try again?" else "" }
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
private fun SettingsScreen(api: Api, onBack: () -> Unit, onForget: () -> Unit) {
    var who by remember { mutableStateOf<Whoami?>(null) }
    var confirm by remember { mutableStateOf(false) }
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
 * The first thing a new person sees: a real request with photos and what came
 * out of it. "Try this one" loads the same photos and words, so the very first
 * tap on Draw demonstrates the whole idea rather than a blank box.
 */
private object Example {
    // Spoken Persian, the way the kid would say it out loud; it also shows that any language works.
    const val PROMPT = "این دستبند رو بذار روی دستم و صورتیش کن"

    // Resource URIs go through the same shrink-and-upload path as camera shots.
    fun photos(ctx: android.content.Context): List<Uri> = listOf("example_wristband", "example_hand")
        .map { Uri.parse("android.resource://${ctx.packageName}/raw/$it") }
}

@Composable
private fun Example(onTry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("What should I draw?", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text("Tell me, or add photos and say what to do with them", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val ctx = LocalContext.current
            Example.photos(ctx).forEachIndexed { i, uri ->
                if (i > 0) Text("+", style = MaterialTheme.typography.titleLarge)
                AsyncImage(uri, null, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
            }
            Text("→", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
            Image(painterResource(R.drawable.example_result), null, Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(10.dp))
        Text("«${Example.PROMPT}»", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onTry) { Text("Try this one") }
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
