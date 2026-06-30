package ksl.app.swing.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.animation.JsonLinesAnimationOutput
import ksl.animation.TraceFileReader
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The streaming substrate (Phase 0): `TraceFileReader.readStreaming` + `StreamingTraceMiner` mine a trace
 * in a single lazy pass and can stop early on demand — never buffering the whole trace.
 */
class StreamingTraceMinerTest {

    @TempDir
    lateinit var tempRoot: Path

    /** Counts the events it sees — the minimal accumulator. */
    private class CountingAccumulator : TraceAccumulator<Int> {
        private var n = 0
        override fun accept(event: AnimationEvent) { n++ }
        override fun result(): Int = n
    }

    private fun writeTrace(events: Int): Path {
        val file = tempRoot.resolve("count.atf")
        JsonLinesAnimationOutput.toFile(file).use { out ->
            out.writeHeader(AnimationTraceHeader())
            out.writeAll((1..events).map { AnimationEvent.EntityCreated(it.toDouble(), it.toLong(), "E") })
        }
        return file
    }

    @Test
    fun `streaming visits every event exactly once, matching readAll`() {
        val file = writeTrace(events = 500)
        val counter = CountingAccumulator()
        TraceFileReader.readStreaming(file) { _, events -> StreamingTraceMiner(listOf(counter)).run(events) }
        assertEquals(500, counter.result(), "streamed every event")
        assertEquals(TraceFileReader.readAll(file).second.size, counter.result(), "parity with readAll")
    }

    @Test
    fun `stopWhen breaks the pass early, processing only a prefix`() {
        val file = writeTrace(events = 500)
        val counter = CountingAccumulator()
        val miner = StreamingTraceMiner(listOf(counter), stopWhen = { counter.result() >= 50 })
        TraceFileReader.readStreaming(file) { _, events -> miner.run(events) }
        assertEquals(50, counter.result(), "early stop processed only the prefix, not all 500")
    }

    @Test
    fun `SaturationStop fires after a quiet window and resets on change`() {
        val stop = SaturationStop(window = 3)
        repeat(2) { stop.observe(changed = false) }
        assertFalse(stop.saturated, "not yet saturated after 2 quiet events")
        stop.observe(changed = false)
        assertTrue(stop.saturated, "saturated after the 3rd consecutive quiet event")
        stop.observe(changed = true)
        assertFalse(stop.saturated, "a change resets the quiet run")
    }
}
