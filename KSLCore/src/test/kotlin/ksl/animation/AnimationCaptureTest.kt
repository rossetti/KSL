package ksl.animation

import ksl.examples.book.chapter6.DriveThroughPharmacy
import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  Core-only proof of the animation capture mechanism: a run captured directly with
 *  [AnimationCapture] — no application layer — produces a valid `.atf` trace, restores the model's
 *  null sink on close, and goes silent afterward. This is the published-KSLCore capability that the
 *  app-tier `RunAttachmentIfc` adapter (`AnimationTraceAttachment`) delegates to.
 */
class AnimationCaptureTest {

    @Test
    @DisplayName("capture produces a valid atf trace with markers and content")
    fun captureProducesValidTrace() {
        val model = Model("AnimCaptureTest")
        DriveThroughPharmacy(model)   // process-view: customers are Entities, exercising the ProcessModel emit points
        model.numberOfReplications = 1
        model.lengthOfReplication = 200.0

        val trace = Files.createTempFile("anim-capture-test", ".atf")
        try {
            val capture = AnimationCapture.toFile(model, trace)
            model.simulate()
            capture.close()

            val (header, events) = TraceFileReader.readAll(trace)

            // (1) the header is present and written in the current format generation
            assertEquals(AnimationEvent.FORMAT_VERSION, header.formatVersion)

            // (2) the experiment/replication markers delimit the trace
            assertTrue(events.any { it is AnimationEvent.ExperimentStarted }, "missing ExperimentStarted")
            assertTrue(events.any { it is AnimationEvent.ReplicationStarted }, "missing ReplicationStarted")
            assertTrue(events.any { it is AnimationEvent.ReplicationEnded }, "missing ReplicationEnded")
            assertTrue(events.any { it is AnimationEvent.ExperimentEnded }, "missing ExperimentEnded")

            // (3) real content was captured: customers were created (the Entity init emit point)
            assertTrue(events.any { it is AnimationEvent.EntityCreated }, "expected EntityCreated content events")

            // (4) seize/queue activity flowed through the pharmacist resource and its queue
            assertTrue(
                events.any { it is AnimationEvent.SeizeQueued } || events.any { it is AnimationEvent.QueueLengthChanged },
                "expected seize/queue activity"
            )
        } finally {
            Files.deleteIfExists(trace)
        }
    }

    @Test
    @DisplayName("close restores the null sink and capture goes silent")
    fun closeRestoresNullSinkAndSilences() {
        val model = Model("AnimCaptureSilenceTest")
        DriveThroughPharmacy(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0

        val trace = Files.createTempFile("anim-capture-silence", ".atf")
        try {
            val capture = AnimationCapture.toFile(model, trace)
            model.simulate()
            capture.close()

            // (1) the model's sink is restored to the inert null sink
            assertSame(NullAnimationSink, model.animationSink, "close must restore the null sink")

            // (2) the trace is complete, and a post-close emit is swallowed (no growth / no open-handle race)
            val (_, before) = TraceFileReader.readAll(trace)
            model.animationSink.emit(AnimationEvent.ExperimentEnded(0.0, "afterClose"))
            val (_, after) = TraceFileReader.readAll(trace)
            assertEquals(before.size, after.size, "no event may be written after close()")
        } finally {
            Files.deleteIfExists(trace)
        }
    }
}
