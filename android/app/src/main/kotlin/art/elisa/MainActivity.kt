package art.elisa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import art.elisa.ui.DrawScreen
import art.elisa.ui.PairScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Pictures load through the same client as the API, DNS-free for sslip names.
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(this).okHttpClient(httpClient).build())
        val store = Store(this)
        setContent {
            MaterialTheme(colorScheme = Palette) {
                Surface {
                    var code by remember { mutableStateOf(store.code) }
                    val current = code
                    if (current == null) {
                        PairScreen(store) { code = it }
                    } else {
                        DrawScreen(Api(store.serverUrl, current), store) {
                            store.code = null
                            code = null
                        }
                    }
                }
            }
        }
    }
}

// Warm, high-contrast and cheerful: this is a kid's drawing app.
private val Palette = lightColorScheme(
    primary = Color(0xFFE91E63),
    onPrimary = Color.White,
    secondary = Color(0xFF7C4DFF),
    background = Color(0xFFFFF8F0),
    surface = Color(0xFFFFF8F0),
    surfaceVariant = Color(0xFFFFE4EC),
)
