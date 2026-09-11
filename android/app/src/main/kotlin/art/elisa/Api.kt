package art.elisa

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One exchange in a drawing: what was said and attached, what came back. */
@Serializable
data class Turn(
    val prompt: String = "",
    val kind: String = "draw",
    val photos: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    val text: String = "",
)

@Serializable
data class Drawing(
    val id: String,
    val text: String = "",
    val images: List<String> = emptyList(),
    val photos: List<String> = emptyList(),
    val turns: List<Turn> = emptyList(),
    val error: String? = null,
)

@Serializable
data class Whoami(val name: String, val drawings_left_this_hour: Int)

@Serializable
data class AppVersion(val versionCode: Int, val versionName: String, val url: String)

@Serializable
private data class PromptBody(val prompt: String, val mode: String = "draw")

class ApiError(val status: Int, message: String) : IOException(message)

/**
 * The server's name spells out its own address (`1-2-3-4.sslip.io`), so the
 * app never needs a DNS server to find it. That matters where DNS is
 * intercepted: a browser quietly upgrades to encrypted DNS and gets through,
 * a plain app does not. Other names still go to the system resolver.
 */
object SslipDns : Dns {
    private val pattern = Regex("""^(\d{1,3})-(\d{1,3})-(\d{1,3})-(\d{1,3})\.sslip\.io$""")

    fun address(host: String): java.net.InetAddress? =
        pattern.matchEntire(host)?.let { m ->
            java.net.InetAddress.getByAddress(host, m.groupValues.drop(1).map { it.toInt().toByte() }.toByteArray())
        }

    override fun lookup(hostname: String) = address(hostname)?.let { listOf(it) } ?: Dns.SYSTEM.lookup(hostname)
}

/** One HTTP client for the API and for pictures, so both skip DNS the same way. */
val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(SslipDns)
        // A drawing is one Codex turn: 30-90 s is normal, so wait well past that.
        .readTimeout(4, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()
}

/** Thin client for the elisart server. The invite code is the bearer token. */
class Api(val baseUrl: String, private val code: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = httpClient

    fun imageUrl(d: Drawing, name: String) = "$baseUrl/drawings/${d.id}/images/$name"
    fun photoUrl(d: Drawing, name: String) = "$baseUrl/drawings/${d.id}/photos/$name"

    /** A Coil request for one of our URLs, carrying the code like every other call. */
    fun authed(url: String, ctx: android.content.Context) = coil.request.ImageRequest.Builder(ctx)
        .data(url).addHeader("Authorization", authHeader).crossfade(true).build()
    val authHeader get() = "Bearer $code"

    suspend fun whoami(): Whoami = get("/whoami")
    suspend fun appVersion(): AppVersion = get("/app/version")
    suspend fun drawings(): List<Drawing> = get("/drawings")
    suspend fun deleteDrawing(id: String) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url("$baseUrl/drawings/$id").header("Authorization", authHeader).delete().build())
            .execute().use { if (!it.isSuccessful) throw ApiError(it.code, friendly(it.code, "")) }
    }

    /** Raw bytes of one of our pictures, for saving to Photos or sharing. */
    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("Authorization", authHeader).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiError(resp.code, friendly(resp.code, ""))
            resp.body!!.bytes()
        }
    }
    /** [ask] sends a question to be answered in words instead of a picture. */
    suspend fun newDrawing(prompt: String, photos: List<ByteArray> = emptyList(), ask: Boolean = false): Drawing =
        post("/drawings", body(prompt, photos, ask))
    suspend fun continueDrawing(id: String, prompt: String, photos: List<ByteArray> = emptyList(), ask: Boolean = false): Drawing =
        post("/drawings/$id/turns", body(prompt, photos, ask))

    /** JSON when there is only text (what the server always accepted), multipart with photos. */
    private fun body(prompt: String, photos: List<ByteArray>, ask: Boolean): RequestBody {
        val mode = if (ask) "ask" else "draw"
        if (photos.isEmpty()) {
            return json.encodeToString(PromptBody.serializer(), PromptBody(prompt, mode))
                .toRequestBody("application/json".toMediaType())
        }
        val b = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("prompt", prompt).addFormDataPart("mode", mode)
        photos.forEachIndexed { i, bytes ->
            b.addFormDataPart("photos", "photo-$i.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
        }
        return b.build()
    }

    private suspend inline fun <reified T> get(path: String): T =
        exec(Request.Builder().url(baseUrl + path).header("Authorization", authHeader).build())

    private suspend inline fun <reified T> post(path: String, payload: RequestBody): T =
        exec(Request.Builder().url(baseUrl + path).header("Authorization", authHeader).post(payload).build())

    private suspend inline fun <reified T> exec(req: Request): T = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiError(resp.code, friendly(resp.code, text))
            json.decodeFromString<T>(text)
        }
    }

    private fun friendly(status: Int, body: String): String = when (status) {
        401 -> "That code doesn't work. Check it and try again."
        429 -> "That's enough drawings for this hour. Try again a bit later."
        else -> runCatching { json.decodeFromString<ErrorBody>(body).detail }.getOrNull()
            ?: "Something went wrong ($status). Try again."
    }

    @Serializable
    private data class ErrorBody(val detail: String)
}
