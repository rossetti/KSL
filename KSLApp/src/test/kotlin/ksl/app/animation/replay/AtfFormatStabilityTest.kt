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

package ksl.app.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.animation.MoverMode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Locks the byte-level stability of the `.atf` trace format.
 *
 * The animation event model was split so that the same declarations compile for a non-JVM target and
 * drive a web renderer. That refactoring must not have perturbed a single byte of what KSL writes:
 * traces already in the wild have to keep loading, and a renderer written against the documented format
 * has to keep parsing them.
 *
 * Byte-for-byte equality is asserted here, on the JVM, because the JVM writer is the only thing that
 * ever produces a `.atf`. It deliberately is *not* asserted for Kotlin/JS: that platform prints a
 * `Double` of integral value without a fractional part (`0.0` becomes `0`), which is semantically
 * identical and harmless for a reader, but textually different. The web side asserts semantic
 * round-tripping instead — see `SharedCodecTest` in KSLAnimationCore.
 */
class AtfFormatStabilityTest {

    /**
     * One representative line per event shape that carries something the refactoring touched: defaulted
     * `z` coordinates, nullable location names, an enum-valued mover mode, non-finite coordinates, the
     * conveyor structure, and the nested list payloads.
     */
    private val goldenLines = listOf(
        """{"event":"ExperimentStarted","simTime":0.0,"experimentName":"Experiment_1","numberOfReplications":3}""",
        """{"event":"EntityCreated","simTime":1.5,"entityId":1,"entityType":"Patient"}""",
        """{"event":"DelayStarted","simTime":1.5,"entityId":1,"duration":4.0,"arrivalTime":5.5,"suspensionName":"Triage"}""",
        """{"event":"ResourceStateChanged","simTime":1.5,"resourceName":"Nurse","state":"Busy","busyUnits":1,"capacity":2}""",
        """{"event":"MoveStarted","simTime":2.0,"entityId":1,"fromX":0.0,"fromY":0.0,"toX":10.0,"toY":20.0,"velocity":5.0,"duration":4.0,"arrivalTime":6.0,"fromZ":0.0,"toZ":0.0,"fromLocationName":"Door","toLocationName":"Exam"}""",
        """{"event":"MoveCompleted","simTime":6.0,"entityId":1,"toX":NaN,"toY":NaN,"toZ":0.0}""",
        """{"event":"ConveyorDefined","simTime":0.0,"conveyorName":"Belt","anchorLocations":["In","Out"],"anchorCells":[0,12]}""",
        """{"event":"ConveyorItemMoved","simTime":3.25,"entityId":4,"conveyorName":"Belt","cellIndex":7}""",
        """{"event":"ResponseObserved","simTime":9.0,"responseName":"WaitTime","value":2.5,"count":4.0,"average":1.75,"min":0.5,"max":2.5}""",
        """{"event":"AgentPositionChanged","simTime":1.0,"agentName":"Boid_1","projectionName":"sky","x":3.5,"y":4.5,"z":0.0}""",
        """{"event":"NetworkDefined","simTime":0.0,"name":"graph","nodes":[{"id":"a","x":0.0,"y":0.0},{"id":"b","x":1.0,"y":2.0}],"edges":[{"from":"a","to":"b","weight":1.0}]}""",
        """{"event":"FlowFieldDefined","simTime":0.0,"spaceName":"grid","cols":2,"rows":2,"cellSize":1.0,"originX":0.0,"originY":0.0,"cells":[{"col":0,"row":0,"distance":2.0}],"maxDistance":2.0}""",
        """{"event":"PlannedPath","simTime":1.0,"agentName":"Drone_1","points":[{"x":0.0,"y":0.0},{"x":5.0,"y":5.0}]}""",
        """{"event":"MarkerPulsed","simTime":4.0,"x":1.0,"y":2.0,"z":0.0,"holdTime":1.5,"label":"drop","colorHex":"#1f77b4"}""",
        """{"event":"EnteredNetwork","simTime":0.5,"entityId":9,"networkName":"Shop","ingressName":"In","qObjectType":2,"qObjectTypeName":"Rush"}""",
    )

    @Test
    @DisplayName("every .atf event line re-encodes to exactly the same bytes")
    fun eventLinesReEncodeByteIdentically() {
        for (line in goldenLines) {
            val decoded = AnimationEvent.decodeFromLine(line)
            assertEquals(line, AnimationEvent.encodeToLine(decoded), "byte-level format drift for: $line")
        }
    }

    @Test
    @DisplayName("the .atf header re-encodes to exactly the same bytes")
    fun headerReEncodesByteIdentically() {
        val line = """{"formatVersion":1,"baseTimeUnit":"MINUTE","kslVersion":null,"description":"Clinic"}"""
        assertEquals(line, AnimationTraceHeader.decodeFromLine(line).encodeToLine())
    }

    @Test
    @DisplayName("a mover event round-trips its enum mode and carried load")
    fun spatialElementMovedReEncodesByteIdentically() {
        val line = """{"event":"SpatialElementMoved","simTime":2.0,"name":"AGV_1","fromX":0.0,"fromY":0.0,"fromZ":0.0,"toX":10.0,"toY":0.0,"toZ":0.0,"velocity":2.0,"duration":5.0,"arrivalTime":7.0,"fromLocationName":"Dock","toLocationName":"Cell","mode":"TRANSPORTING","carriedEntityId":3,"carriedEntityType":"Pallet"}"""
        val decoded = AnimationEvent.decodeFromLine(line) as AnimationEvent.SpatialElementMoved
        assertEquals(MoverMode.TRANSPORTING, decoded.mode)
        assertEquals(3L, decoded.carriedEntityId)
        assertEquals(line, AnimationEvent.encodeToLine(decoded))
    }

    @Test
    @DisplayName("the trace format version is still 1 — no format change was made")
    fun formatVersionIsUnchanged() {
        assertEquals(1, AnimationEvent.FORMAT_VERSION)
    }
}
