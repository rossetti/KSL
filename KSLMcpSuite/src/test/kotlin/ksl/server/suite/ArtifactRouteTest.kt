package ksl.server.suite

import kotlinx.coroutines.runBlocking
import ksl.service.store.ArtifactStore
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * The artifact-serving route, exercised against a really-listening server rather than a mock —
 * the point of the route is that a link handed to a user actually resolves, and the traversal
 * guard is security-relevant enough to be worth testing on the real thing.
 */
class ArtifactRouteTest {

    /** Starts the suite's HTTP app on an ephemeral port, runs [block] against it, and stops it. */
    private fun withServer(store: ArtifactStore?, block: (Int) -> Unit) {
        val server = KslSuiteMcpServer.create(
            capabilities = emptyList(),
            host = "127.0.0.1",
            port = 0, // ephemeral: never collide with a real server on this machine
            artifactStore = store,
        )
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            block(port)
        } finally {
            server.stop(0, 0)
        }
    }

    private fun get(port: Int, path: String): Pair<Int, String> {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        return try {
            val code = connection.responseCode
            val body = if (code == 200) connection.inputStream.bufferedReader().readText() else ""
            code to body
        } finally {
            connection.disconnect()
        }
    }

    private fun storeWithArtifacts(): ArtifactStore {
        val root = Files.createTempDirectory("suite-artifact-route")
        val store = ArtifactStore(root, "http://127.0.0.1:3001")
        val dir = store.dirFor("res1")
        Files.writeString(dir.resolve("report.html"), "<html>MCB</html>")
        Files.createDirectories(dir.resolve("plots"))
        Files.writeString(dir.resolve("plots").resolve("welch.png"), "PNGDATA")
        return store
    }

    @Test
    @DisplayName("a stored artifact is served at the URL its ref advertises")
    fun servesAnArtifact() {
        val store = storeWithArtifacts()
        withServer(store) { port ->
            val (code, body) = get(port, "/results/res1/artifacts/report.html")
            assertEquals(200, code)
            assertEquals("<html>MCB</html>", body)
        }
    }

    @Test
    @DisplayName("a nested artifact resolves through the tail path parameter")
    fun servesANestedArtifact() {
        val store = storeWithArtifacts()
        withServer(store) { port ->
            val (code, body) = get(port, "/results/res1/artifacts/plots/welch.png")
            assertEquals(200, code)
            assertEquals("PNGDATA", body)
        }
    }

    @Test
    @DisplayName("a path-traversal attempt is refused, not served")
    fun refusesTraversal() {
        val store = storeWithArtifacts()
        withServer(store) { port ->
            // ArtifactStore.resolve normalizes and confines to the artifacts dir; anything escaping
            // it returns null, which this route reports as 404 rather than reading the file.
            val (code, _) = get(port, "/results/res1/artifacts/../../result.json")
            assertEquals(404, code)
        }
    }

    @Test
    @DisplayName("an unknown artifact is a 404")
    fun unknownArtifactIs404() {
        val store = storeWithArtifacts()
        withServer(store) { port ->
            assertEquals(404, get(port, "/results/res1/artifacts/nope.html").first)
        }
    }

    @Test
    @DisplayName("with no artifact store the route is absent entirely (book/code-only deployment)")
    fun routeAbsentWithoutStore() {
        withServer(null) { port ->
            assertEquals(404, get(port, "/results/res1/artifacts/report.html").first)
        }
    }
}
