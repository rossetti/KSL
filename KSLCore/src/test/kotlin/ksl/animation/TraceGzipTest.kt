package ksl.animation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies a `.atf.gz` trace is transparently gzip-compressed on write, gunzipped on read, and is
 *  substantially smaller than the plain form (8F.8). */
class TraceGzipTest {

    private fun write(path: Path, header: AnimationTraceHeader, events: List<AnimationEvent>) {
        val out = JsonLinesAnimationOutput.toFile(path)
        try {
            out.writeHeader(header)
            out.writeAll(events)
        } finally {
            out.close()
        }
    }

    @Test
    fun `gzip trace round-trips and is smaller than plain`() {
        val dir = Files.createTempDirectory("ksl-gz")
        val plain = dir.resolve("t.atf")
        val gz = dir.resolve("t.atf.gz")
        val header = AnimationTraceHeader(description = "gz-test")
        val events = (0 until 3000).map { AnimationEvent.QueueLengthChanged(it.toDouble(), "WorkerQ", it % 9) }

        write(plain, header, events)
        write(gz, header, events)

        val (h, ev) = TraceFileReader.readAll(gz)
        assertEquals("gz-test", h.description)
        assertEquals(events.size, ev.size, "all events read back from the gzip trace")
        assertEquals(events.first(), ev.first())
        assertEquals(events.last(), ev.last())
        assertTrue(
            Files.size(gz) < Files.size(plain) / 2,
            "gzip should be far smaller: ${Files.size(gz)} vs ${Files.size(plain)}"
        )
    }
}
