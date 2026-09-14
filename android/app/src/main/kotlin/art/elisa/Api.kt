package art.elisa

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    /** The request the server is still working on, if any. */
    val pending: Turn? = null,
)

@Serializable
data class Whoami(val name: String, val drawings_left_this_hour: Int)

@Serializable
data class AppVersion(val versionCode: Int, val versionName: String, val url: String)

@Serializable
private data class PromptBody(val prompt: String, val mode: String = "draw", val id: String? = null)

/** An answer from the server that is not the one hoped for; [status] 0 means it never got there. */
class ApiError(val status: Int, message: String) : IOException(message)

/**
 * How long to keep asking after a request, counted in polls actually made, not
 * on the clock: a frozen app makes no polls, and the minutes it spent frozen
 * must not count against it. A drawing takes 30-90 s and the server gives up
 * at five minutes, so the deadline sits past that; the grace is how long to
 * look for a request whose send died before deciding it was never received.
 */
data class Patience(val pollMs: Long = 2_500, val graceMs: Long = 20_000, val deadlineMs: Long = 6 * 60_000)

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
class Api(val baseUrl: String, private val code: String, private val patience: Patience = Patience()) {
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
    /**
     * One request, start to finish, on drawing [on] or a new one. The server
     * answers at once and draws in the background; we poll the drawing until
     * the reply lands. So a connection that dies half-way (a minute is a long
     * time on a bad network) costs nothing: the next poll finds the picture. A
     * new drawing carries an id chosen here, so even a request whose answer was
     * lost can be found again. [ask] wants words back instead of a picture.
     */
    suspend fun turn(on: Drawing?, prompt: String, photos: List<ByteArray> = emptyList(), ask: Boolean = false): Drawing {
        val id = on?.id ?: newId()
        val path = if (on == null) "/drawings?wait=false" else "/drawings/$id/turns?wait=false"
        val lost = try {
            post<Drawing>(path, body(prompt, photos, ask, id.takeIf { on == null }))
            null
        } catch (e: ApiError) {
            // 409: the server is still on this drawing's last request, most likely
            // this very one sent again after a drop. Its picture is the one to wait for.
            if (e.status == 409) null else throw e
        } catch (e: IOException) {
            e
        }
        return awaitTurn(id, on?.turns?.size ?: 0, lost)
    }

    /**
     * Polls drawing [id] until it has more than [before] turns. [lost] is the
     * error the send died with, when it is not known whether the server got it.
     */
    suspend fun awaitTurn(id: String, before: Int, lost: IOException? = null): Drawing {
        var landed = lost == null
        var answered = false
        var lastFailure: IOException? = lost
        var tried = 0
        while (true) {
            delay(patience.pollMs)
            val elapsed = ++tried * patience.pollMs
            val d = try {
                get<Drawing>("/drawings/$id")
            } catch (e: ApiError) {
                // A new drawing the server never heard of: keep looking during the grace.
                if (e.status == 404 && !landed) { answered = true; null } else throw e
            } catch (e: IOException) {
                lastFailure = e
                null
            }
            if (d != null) {
                answered = true
                if (d.turns.size > before) return d
                if (d.pending != null) landed = true
                else if (landed) throw ApiError(502, d.error ?: "Nothing came back. Try again.")
            }
            if (!landed && elapsed > patience.graceMs) {
                // Never reached the server: a real outage if nothing answered at all,
                // otherwise a send that simply has to be repeated.
                throw if (answered) ApiError(0, "That didn't get through. Try again.")
                else lastFailure ?: ApiError(0, "That didn't get through. Try again.")
            }
            if (landed && elapsed > patience.deadlineMs) {
                throw lastFailure ?: ApiError(504, "This is taking too long. Check the gallery in a minute.")
            }
        }
    }

    private fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    /** JSON when there is only text (what the server always accepted), multipart with photos. */
    private fun body(prompt: String, photos: List<ByteArray>, ask: Boolean, id: String?): RequestBody {
        val mode = if (ask) "ask" else "draw"
        if (photos.isEmpty()) {
            return json.encodeToString(PromptBody.serializer(), PromptBody(prompt, mode, id))
                .toRequestBody("application/json".toMediaType())
        }
        val b = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("prompt", prompt).addFormDataPart("mode", mode)
        id?.let { b.addFormDataPart("id", it) }
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
