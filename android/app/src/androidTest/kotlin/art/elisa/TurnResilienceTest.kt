package art.elisa

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The failure that started this: a drawing takes a minute, and Android tore the
 * socket down half-way ("Software caused connection abort") while the server
 * kept painting. These run on the emulator against a fake server that behaves
 * like ours and drops connections on purpose, and check that the app still
 * ends up with the picture, never draws twice, and tells a lost send from an
 * outage.
 */
@RunWith(AndroidJUnit4::class)
class TurnResilienceTest {
    private lateinit var server: MockWebServer

    // Seconds, not minutes: the same logic, hurried.
    private val hurry = Patience(pollMs = 100, graceMs = 1_500, deadlineMs = 10_000)

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private fun api() = Api(server.url("/").toString().trimEnd('/'), "ART-TEST-CODE", hurry)

    private fun pending(id: String, turns: Int = 0) =
        """{"id":"$id","turns":[${List(turns) { TURN }.joinToString(",")}],"pending":{"prompt":"a cat"}}"""
    private fun done(id: String, turns: Int) =
        """{"id":"$id","turns":[${List(turns) { TURN }.joinToString(",")}],"images":["001.png"]}"""
    private fun failed(id: String, why: String) = """{"id":"$id","turns":[],"error":"$why"}"""
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)
    // Reads the request, then closes without answering: what a dropped socket looks
    // like from the phone. (DISCONNECT_AT_START is only honoured through peek(), not
    // a dispatcher, and would come out as an empty 200.)
    private fun drop() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)

    /** Answers every request with [route]; POST bodies for new drawings have their id read out. */
    private fun serve(route: (RecordedRequest, String) -> MockResponse) {
        var id: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST" && request.path == "/drawings?wait=false") {
                    id = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["id"]?.jsonPrimitive?.content
                }
                return route(request, id ?: "")
            }
        }
    }

    @Test fun the_picture_arrives_even_when_the_send_loses_its_connection() = runBlocking {
        val posts = AtomicInteger()
        val polls = AtomicInteger()
        serve { req, id ->
            when {
                // The server took the request and started drawing; the phone's socket died.
                req.method == "POST" && posts.incrementAndGet() == 1 -> drop()
                // Sent again (by OkHttp or by us): already running, same drawing.
                req.method == "POST" -> json(pending(id), 202)
                req.path == "/drawings/$id" && polls.incrementAndGet() < 3 -> json(pending(id))
                req.path == "/drawings/$id" -> json(done(id, 1))
                else -> json("""{"detail":"no such drawing"}""", 404)
            }
        }
        val d = api().turn(null, "a cat")
        assertEquals(listOf("001.png"), d.turns.single().images)
        assertNull(d.pending)
        assertTrue("polled until it was done", polls.get() >= 3)
        // The app itself never re-posts: it goes looking for the drawing it named.
        assertEquals(12, d.id.length)
    }

    @Test fun a_change_sent_again_while_the_server_is_still_on_it_waits_for_that_picture() = runBlocking {
        val open = Drawing(id = "abcdef012345", turns = listOf(Turn(prompt = "a cat", images = listOf("001.png"))))
        val polls = AtomicInteger()
        serve { req, _ ->
            when {
                req.method == "POST" -> json("""{"detail":"still working on the last request"}""", 409)
                polls.incrementAndGet() < 2 -> json(pending(open.id, turns = 1))
                else -> json(done(open.id, 2))
            }
        }
        val d = api().turn(open, "make it blue")
        assertEquals(2, d.turns.size)
    }

    @Test fun a_send_that_never_reached_the_server_is_reported_as_such_not_as_an_outage() = runBlocking {
        serve { req, _ -> if (req.method == "POST") drop() else json("""{"detail":"no such drawing"}""", 404) }
        val t = System.currentTimeMillis()
        try {
            api().turn(null, "a cat")
            fail("should not have produced a drawing")
        } catch (e: ApiError) {
            assertEquals(0, e.status)
            assertTrue(e.message!!.contains("didn't get through"))
        }
        assertTrue("kept looking for the grace period", System.currentTimeMillis() - t >= hurry.graceMs)
    }

    @Test fun an_outage_surfaces_as_the_network_error_so_the_diagnosis_button_shows() = runBlocking {
        serve { _, _ -> drop() }
        try {
            api().turn(null, "a cat")
            fail("should not have produced a drawing")
        } catch (e: ApiError) {
            fail("an outage must not look like a server answer: $e")
        } catch (e: IOException) {
            // What DrawScreen turns into "Couldn't reach the server" + "Send a diagnosis".
        }
    }

    @Test fun the_servers_failure_comes_back_as_its_own_words() = runBlocking {
        val polls = AtomicInteger()
        serve { req, id ->
            when {
                req.method == "POST" -> json(pending(id), 202)
                polls.incrementAndGet() < 2 -> json(pending(id))
                else -> json(failed(id, "codex timed out after 300s"))
            }
        }
        try {
            api().turn(null, "a cat")
            fail("should not have produced a drawing")
        } catch (e: ApiError) {
            assertEquals(502, e.status)
            assertEquals("codex timed out after 300s", e.message)
        }
    }

    @Test fun a_request_left_running_last_time_is_picked_up_again() = runBlocking {
        val polls = AtomicInteger()
        serve { _, _ -> if (polls.incrementAndGet() < 2) json(pending("abcdef012345")) else json(done("abcdef012345", 1)) }
        val d = api().awaitTurn("abcdef012345", before = 0)
        assertEquals(1, d.turns.size)
    }

    private companion object {
        const val TURN = """{"prompt":"a cat","images":["001.png"],"text":"A cat!"}"""
    }
}
