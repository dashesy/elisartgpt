package art.elisa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import art.elisa.Api
import art.elisa.Store
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import art.elisa.ApiError
import art.elisa.Diagnosis

/** First launch: type the invite code. We check it against the server before saving. */
@Composable
fun PairScreen(store: Store, onPaired: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(store.serverUrl) }
    var showServer by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Kept so "send diagnosis" can include the real exception, not the friendly line.
    var failure by remember { mutableStateOf<Throwable?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun submit() {
        val trimmed = code.trim().uppercase()
        if (trimmed.isEmpty() || busy) return
        busy = true; error = null; failure = null
        scope.launch {
            try {
                store.serverUrl = server
                val who = Api(store.serverUrl, trimmed).whoami()
                store.code = trimmed
                onPaired(trimmed)
                error = "Hi ${who.name}!"
            } catch (e: ApiError) {
                error = e.message
            } catch (e: Exception) {
                failure = e
                error = "Couldn't reach the server."
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🎨", style = MaterialTheme.typography.displayLarge)
        Text("Elisa Art", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Enter the code you were given", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Code") },
            placeholder = { Text("ART-XXXX-XXXX") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        if (showServer) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = server, onValueChange = { server = it },
                label = { Text("Server") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = ::submit, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text("Let's draw!")
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (failure != null && Diagnosis.available) {
            DiagnosisButton(serverUrl = server, failure = failure, busy = busy)
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { showServer = !showServer }) {
            Text(if (showServer) "Hide server" else "Use a different server")
        }
    }
}


/** One tap: gather the report (a few seconds of probes), then open the mail app with it. */
@Composable
fun DiagnosisButton(serverUrl: String, failure: Throwable?, busy: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    TextButton(
        onClick = {
            working = true
            scope.launch {
                try {
                    Diagnosis.send(ctx, Diagnosis.collect(ctx, serverUrl, failure))
                } finally {
                    working = false
                }
            }
        },
        enabled = !busy && !working,
    ) { Text(if (working) "Checking the connection…" else "Send a diagnosis") }
}
