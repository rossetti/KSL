package ksl.app.session

import ksl.animation.AnimationEvent
import ksl.animation.TraceFileReader
import ksl.examples.book.chapter6.DriveThroughPharmacy
import ksl.simulation.Model
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof of the animation capture pipeline: a real run with an
 * [AnimationTraceAttachment] installed produces a valid `.atf` trace whose header,
 * lifecycle markers, and content events come from the engine emit points
 * (ProcessModel) and the per-element emitters wired by the attachment.
 */
class AnimationTraceAttachmentTest {

    @Test
    fun `a run with the attachment produces a valid atf trace with markers and content`() {
        val model = Model("AnimTraceTest")
        DriveThroughPharmacy(model)   // process-view: customers are Entities, exercising the ProcessModel emit points
        model.numberOfReplications = 1
        model.lengthOfReplication = 200.0

        val trace = Files.createTempFile("anim-trace-test", ".atf")
        try {
            // Install the attachment exactly as Runner would (onAttach before the run, onDetach after),
            // so the lifecycle observer, emitters, and sink are wired for the whole experiment.
            val attachment = AnimationTraceAttachment.replay(trace)
            attachment.onAttach(model, CoroutineScope(Dispatchers.Default))
            model.simulate()
            attachment.onDetach()

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
}
