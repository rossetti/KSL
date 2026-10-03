package ksl.animation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 *  The trace format grows by defaulted fields and new event types. A reader must therefore ignore a
 *  field it does not know and skip an event type it does not know, or every addition breaks every
 *  older reader; and a trace written before the additions must still decode.
 */
class TraceCompatibilityTest {

    @Test
    fun anUnknownFieldIsIgnoredAndAnUnknownEventTypeIsSkipped() {
        val trace = Files.createTempFile("compat", ".atf")
        try {
            Files.writeString(
                trace,
                AnimationTraceHeader(baseTimeUnit = "MINUTE").encodeToLine() + "\n" +
                    """{"event":"ReplicationStarted","simTime":0.0,"replicationNumber":1,"aFieldFromTheFuture":42}""" + "\n" +
                    """{"event":"AnEventFromTheFuture","simTime":1.0,"whatever":"x"}""" + "\n" +
                    """{"event":"ReplicationEnded","simTime":2.0,"replicationNumber":1}""" + "\n"
            )
            val (_, events) = TraceFileReader.readAll(trace)
            assertEquals(2, events.size)
            val reader = TraceFileReader(Files.newBufferedReader(trace))
            reader.use {
                it.readHeader()
                it.events().toList()
                assertEquals(1, it.numSkippedUnknownEvents)
            }
        } finally {
            Files.deleteIfExists(trace)
        }
    }

    @Test
    fun aMalformedLineStillFails() {
        val trace = Files.createTempFile("malformed", ".atf")
        try {
            Files.writeString(trace, AnimationTraceHeader().encodeToLine() + "\n" + "{not json\n")
            assertThrows(Exception::class.java) { TraceFileReader.readAll(trace) }
        } finally {
            Files.deleteIfExists(trace)
        }
    }

    @Test
    fun aGuidedPathTraceWrittenByR17StillDecodes() {
        val resource: Path = Paths.get(javaClass.getResource("/animation/r17-simpleagv.atf.gz")!!.toURI())
        val (header, events) = TraceFileReader.readAll(resource)
        assertEquals(1, header.formatVersion)
        val def = events.filterIsInstance<AnimationEvent.GuidedPathDefined>().single()
        // Fields added after R1.7 take their defaults.
        assertTrue(def.transporters.isEmpty())
        assertTrue(def.spaceName == null)
        assertTrue(events.filterIsInstance<AnimationEvent.GuidedTransporterMoved>().isNotEmpty())
    }
}
