/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 *  The event emitted when a wait's time limit runs out is part of the trace format: it encodes and
 *  decodes, a trace file containing it reads back, and it is keyed to its entity, so entity capture
 *  filtering keeps or drops it with the rest of that entity's events. The format version is
 *  unchanged, because readers skip an event type they do not know.
 */
class WaitTimedOutTraceTest {

    @Test
    fun waitTimedOutRoundTripsAndIsKeyedToItsEntity() {
        val named = AnimationEvent.WaitTimedOut(12.5, 42L, "decision")
        val unnamed = AnimationEvent.WaitTimedOut(3.0, 7L, null)
        for (event in listOf(named, unnamed)) {
            val line = AnimationEvent.encodeToLine(event)
            assertEquals(event, AnimationEvent.decodeFromLine(line))
        }
        assertEquals(42L, named.entityIdOrNull)
        assertEquals(7L, unnamed.entityIdOrNull)
        assertEquals(1, AnimationTraceHeader().formatVersion)
    }

    @Test
    fun aTraceFileWithWaitTimedOutReadsBack() {
        val trace = Files.createTempFile("timedOut", ".atf")
        try {
            val event = AnimationEvent.WaitTimedOut(10.0, 5L, "gate")
            Files.writeString(
                trace,
                AnimationTraceHeader().encodeToLine() + "\n" + AnimationEvent.encodeToLine(event) + "\n"
            )
            val (_, events) = TraceFileReader.readAll(trace)
            assertEquals(listOf<AnimationEvent>(event), events)
        } finally {
            Files.deleteIfExists(trace)
        }
    }
}
