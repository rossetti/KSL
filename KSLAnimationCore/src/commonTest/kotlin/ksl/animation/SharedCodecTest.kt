/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.animation

import ksl.app.animation.geom.BoundingBox
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.ReplayModel
import ksl.app.swing.animation.playback.PlaybackController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that the animation core behaves identically off the JVM.
 *
 * The point is not to re-test replay semantics — the JVM suites in KSLApp and KSLAppSwingAnimation own
 * that, and these are the same source files. The point is that the *platform* does not change the
 * answers: the trace codec round-trips, a trace still indexes into a queryable model, and the playback
 * clock still advances the same way.
 *
 * Trace lines are embedded rather than read from disk because there is no filesystem here.
 */
class SharedCodecTest {

    /** Lines lifted verbatim from a captured `.atf`, so the format under test is the real one. */
    private val traceLines = listOf(
        """{"event":"ExperimentStarted","simTime":0.0,"experimentName":"Experiment_1","numberOfReplications":1}""",
        """{"event":"ReplicationStarted","simTime":0.0,"replicationNumber":1}""",
        """{"event":"EntityCreated","simTime":1.5,"entityId":1,"entityType":"Patient"}""",
        """{"event":"SeizeQueued","simTime":1.5,"entityId":1,"resourceName":"Nurse","queueName":"Nurse:Q","amountRequested":1}""",
        """{"event":"ResourceStateChanged","simTime":1.5,"resourceName":"Nurse","state":"Busy","busyUnits":1,"capacity":1}""",
        """{"event":"SeizeAllocated","simTime":1.5,"entityId":1,"resourceName":"Nurse","amountAllocated":1}""",
        """{"event":"QueueLengthChanged","simTime":1.5,"queueName":"Nurse:Q","length":0}""",
        """{"event":"MoveStarted","simTime":2.0,"entityId":1,"fromX":0.0,"fromY":0.0,"toX":10.0,"toY":20.0,"velocity":5.0,"duration":4.0,"arrivalTime":6.0,"fromZ":0.0,"toZ":0.0,"fromLocationName":null,"toLocationName":null}""",
        """{"event":"Released","simTime":6.0,"entityId":1,"resourceName":"Nurse","amountReleased":1}""",
        """{"event":"EntityDisposed","simTime":6.0,"entityId":1}""",
        """{"event":"ReplicationEnded","simTime":10.0,"replicationNumber":1}""",
    )

    /**
     * Re-encoding is asserted to be **semantically** stable, not textually identical.
     *
     * Kotlin/JS prints a `Double` whose value is integral without a fractional part, so a `simTime` of
     * `0.0` comes back as `0` where the JVM writes `0.0`. The two documents parse to the same event, and
     * a reader is unaffected, but the bytes differ. Byte-for-byte stability is therefore a property of
     * the JVM writer — which is the only thing that ever writes a `.atf` — and is asserted there
     * instead; see `AtfFormatStabilityTest` in KSLApp.
     */
    @Test
    fun eventCodecRoundTripsOnJs() {
        for (line in traceLines) {
            val decoded = AnimationEvent.decodeFromLine(line)
            val reDecoded = AnimationEvent.decodeFromLine(AnimationEvent.encodeToLine(decoded))
            assertEquals(decoded, reDecoded, "re-encoding must preserve the event")
        }
    }

    /** Guards the specific difference above, so it is a recorded expectation rather than a surprise. */
    @Test
    fun integralDoublesLoseTheirFractionalPartOnJs() {
        val decoded = AnimationEvent.decodeFromLine(traceLines[0])
        val reEncoded = AnimationEvent.encodeToLine(decoded)
        assertTrue(
            """"simTime":0""" in reEncoded,
            "Kotlin/JS is expected to print an integral Double without a fractional part; got $reEncoded"
        )
        assertEquals(0.0, AnimationEvent.decodeFromLine(reEncoded).simTime, "and it must still read back as 0.0")
    }

    @Test
    fun headerCodecRoundTripsOnJs() {
        val line = """{"formatVersion":1,"baseTimeUnit":"MINUTE","kslVersion":null,"description":"Clinic"}"""
        val header = AnimationTraceHeader.decodeFromLine(line)
        assertEquals(1, header.formatVersion)
        assertEquals("MINUTE", header.baseTimeUnit)
        assertEquals(header, AnimationTraceHeader.decodeFromLine(header.encodeToLine()))
    }

    /**
     * Coordinate-free spatial models emit NaN positions, which are not legal JSON. The codec sets
     * `allowSpecialFloatingPointValues`, and this asserts that holds off the JVM too — it is the reason
     * the trace format needed no change to be readable by a browser.
     */
    @Test
    fun nonFiniteCoordinatesSurviveTheCodecOnJs() {
        val line = """{"event":"MoveCompleted","simTime":3.0,"entityId":7,"toX":NaN,"toY":NaN,"toZ":0.0}"""
        val decoded = AnimationEvent.decodeFromLine(line) as AnimationEvent.MoveCompleted
        assertTrue(decoded.toX.isNaN(), "NaN must survive decoding")
        // Bare NaN is not legal JSON, so this only works because the codec opts into it. Assert it makes
        // the round trip rather than comparing text (see the Double-formatting note above).
        val reDecoded = AnimationEvent.decodeFromLine(AnimationEvent.encodeToLine(decoded)) as AnimationEvent.MoveCompleted
        assertTrue(reDecoded.toX.isNaN() && reDecoded.toY.isNaN(), "NaN must survive re-encoding")
        assertEquals(3.0, reDecoded.simTime)
    }

    @Test
    fun layoutCodecRoundTripsOnJs() {
        val layout = AnimationLayout(
            title = "Clinic",
            width = 640.0,
            height = 380.0,
            objectClasses = listOf(ObjectClassDefinition("Patient", LayoutShape.CIRCLE, "#1f77b4", 10.0)),
            queues = listOf(QueueLayoutElement("Nurse:Q", LayoutPoint(300.0, 200.0), growthDegrees = 90.0)),
            resources = listOf(ResourceLayoutElement("Nurse", LayoutPoint(420.0, 200.0))),
            spaces = listOf(SpatialSpaceDescriptor.Continuous("sky", 0.0, 100.0, 0.0, 100.0)),
        )
        val reparsed = AnimationLayout.fromJson(layout.toJson())
        assertEquals(layout, reparsed)
    }

    @Test
    fun traceIndexesIntoAQueryableModelOnJs() {
        val events = traceLines.map { AnimationEvent.decodeFromLine(it) }
        val model = ReplayModel.build(AnimationSource(null, AnimationTraceHeader(), events))

        assertEquals(0.0..10.0, model.timeRange)
        assertEquals(1, model.entityCount)
        assertEquals("Patient", model.entityTypeOf(1L))

        // Resource state is a step function: unknown before the first observation, then held.
        assertNull(model.resourceStateAt("Nurse", 0.0))
        assertEquals("Busy", model.resourceStateAt("Nurse", 3.0)?.state)

        // The entity is in service while allocated, and free after release.
        assertEquals("Nurse", model.entityServiceResourceAt(1L, 3.0))
        assertNull(model.entityServiceResourceAt(1L, 7.0))

        // Straight-line interpolation over [2, 6] -> halfway at t = 4.
        val mid = assertNotNull(model.entityPositionAt(1L, 4.0))
        assertEquals(5.0, mid.x, 1e-9)
        assertEquals(10.0, mid.y, 1e-9)

        val bounds = assertNotNull(model.coordinateBounds())
        assertEquals(BoundingBox(0.0, 0.0, 10.0, 20.0), bounds)
    }

    @Test
    fun playbackClockAdvancesOnJs() {
        val controller = PlaybackController(0.0..10.0)
        controller.speed = 2.0
        controller.play()
        controller.advanceBy(1.0)
        assertEquals(2.0, controller.currentTime, 1e-9)
        controller.advanceBy(1.0)
        assertEquals(4.0, controller.currentTime, 1e-9)

        // Runs to the end and stops when not looping.
        controller.advanceBy(100.0)
        assertEquals(10.0, controller.currentTime, 1e-9)
        assertTrue(!controller.isPlaying)
    }

    @Test
    fun boundingBoxSkipsNonFiniteCoordinates() {
        val box = BoundingBox.of(sequenceOf(1.0 to 2.0, Double.NaN to 5.0, 4.0 to 0.0))
        assertEquals(BoundingBox(1.0, 0.0, 4.0, 2.0), box)
        assertNull(BoundingBox.of(sequenceOf(Double.NaN to Double.NaN)))
    }
}
