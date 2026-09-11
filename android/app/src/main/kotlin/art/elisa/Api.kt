package art.elisa

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
private data class PromptBody(val prompt: String)

class ApiError(val status: Int, message: String) : IOException(message)

/** Thin client for the elisart server. The invite code is the bearer token. */
class Api(private val baseUrl: String, private val code: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        // A drawing is one Codex turn: 30-90 s is normal, so wait well past that.
        .readTimeout(4, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

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
    suspend fun newDrawing(prompt: String, photos: List<ByteArray> = emptyList()): Drawing =
        post("/drawings", body(prompt, photos))
    suspend fun continueDrawing(id: String, prompt: String, photos: List<ByteArray> = emptyList()): Drawing =
        post("/drawings/$id/turns", body(prompt, photos))

    /** JSON when there is only text (what the server always accepted), multipart with photos. */
    private fun body(prompt: String, photos: List<ByteArray>): RequestBody {
        if (photos.isEmpty()) {
            return json.encodeToString(PromptBody.serializer(), PromptBody(prompt))
                .toRequestBody("application/json".toMediaType())
        }
        val b = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("prompt", prompt)
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
