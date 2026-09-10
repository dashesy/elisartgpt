package art.elisa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import coil.compose.AsyncImage
import coil.request.ImageRequest
import art.elisa.Api
import art.elisa.AppVersion
import art.elisa.BuildConfig
import art.elisa.Drawing
import kotlinx.coroutines.launch

/**
 * The whole app after pairing: a prompt box, the current picture, a "change it"
 * box that continues the same drawing, and a gallery of past drawings.
 */
@Composable
fun DrawScreen(api: Api, onForget: () -> Unit) {
    val scope = rememberCoroutineScope()
    val gallery = remember { mutableStateListOf<Drawing>() }
    var current by remember { mutableStateOf<Drawing?>(null) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showGallery by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppVersion?>(null) }
    var confirmForget by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { gallery.addAll(api.drawings()) }
        // Reopen where we left off: the newest drawing, so "Change it" still works.
        if (current == null) current = gallery.firstOrNull { it.images.isNotEmpty() }
        // Sideloaded apps never update themselves; this is the whole update story.
        runCatching { api.appVersion() }.getOrNull()
            ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            ?.let { update = it }
    }

    fun run(block: suspend () -> Drawing) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                val d = block()
                current = d
                gallery.removeAll { it.id == d.id }
                gallery.add(0, d)
                prompt = ""
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong."
            } finally {
                busy = false
            }
        }
    }

    if (confirmForget) {
        // One stray tap must not log a kid out; the drawings stay on the server either way.
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this code?") },
            text = { Text("You'll need to type the code again to draw. Your drawings are kept.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget") } },
            dismissButton = { Button(onClick = { confirmForget = false }) { Text("Keep drawing") } },
        )
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
                IconButton(onClick = { confirmForget = true }) { Icon(Icons.Filled.Logout, "Forget code") }
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            update?.let { UpdateBanner(it) { update = null } }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val d = current
                when {
                    busy -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Drawing… this takes about a minute")
                    }
                    d != null && d.images.isNotEmpty() -> Picture(api, d, d.images.last(), Modifier.fillMaxSize())
                    else -> Text("What should I draw?", style = MaterialTheme.typography.titleLarge)
                }
            }
            current?.text?.takeIf { it.isNotBlank() && !busy }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = { Text(if (current == null) "A purple dragon eating ice cream" else "Change it… make the sky pink") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            enabled = !busy,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cur = current
            if (cur != null) {
                Button(
                    onClick = { run { api.continueDrawing(cur.id, prompt) } },
                    enabled = !busy && prompt.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Filled.Brush, null); Spacer(Modifier.size(6.dp)); Text("Change it") }
                OutlinedButton(
                    onClick = { run { api.newDrawing(prompt) } },
                    enabled = !busy && prompt.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("New drawing") }
            } else {
                Button(
                    onClick = { run { api.newDrawing(prompt) } },
                    enabled = !busy && prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Filled.Brush, null); Spacer(Modifier.size(6.dp)); Text("Draw it!") }
            }
        }
    }
}

/** Opens the APK link in the browser; Android's installer takes over from there. */
@Composable
private fun UpdateBanner(v: AppVersion, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("New version ${v.versionName} is out!", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("Later") }
            Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.url))) }) { Text("Update") }
        }
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
        model = ImageRequest.Builder(ctx)
            .data(api.imageUrl(d, name))
            .addHeader("Authorization", api.authHeader)
            .crossfade(true)
            .build(),
        contentDescription = d.text,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
