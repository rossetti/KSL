/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.service.capability.run

import ksl.app.config.RunConfigurationJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The random-stream affordance on the flattened single-run path (Phase A): the
 * intent-level `replicationSet` maps to a non-overlapping `k × reps` substream
 * advance, set 0 stays byte-identical to a plain run, and `antithetic` is carried
 * into the run overrides.
 */
class RunServiceRandomizationTest {

    @Test
    fun `streamAdvancesFor gives non-overlapping k times reps blocks`() {
        // Each set is a block of `reps` substreams: set 0 -> 0, set 1 -> reps, set k -> k*reps,
        // so the blocks [k*reps, k*reps+reps) never overlap across sets.
        assertEquals(0, RunService.streamAdvancesFor(0, 30))
        assertEquals(30, RunService.streamAdvancesFor(1, 30))
        assertEquals(90, RunService.streamAdvancesFor(3, 30))
        // Reps coerced to >= 1 so a degenerate 0 never collapses distinct sets to the same block.
        assertEquals(2, RunService.streamAdvancesFor(2, 0))
        assertFailsWith<IllegalArgumentException> { RunService.streamAdvancesFor(-1, 30) }
    }

    @Test
    fun `singleRunConfig leaves the default run byte-identical and only perturbs when asked`() {
        val registry = TestBundles.registry()
        try {
            RunService.fromRegistry(registry).use { service ->
                // Baseline: no stream args.
                val base = service.singleRunConfig("MM1", numberOfReplications = 4)
                // replicationSet=0 -> 0 advances, folded to null -> byte-identical config.
                val set0Advances = service.singleRunConfig("MM1", numberOfReplications = 4, streamAdvances = RunService.streamAdvancesFor(0, 4))
                assertEquals(
                    RunConfigurationJson.encode(base),
                    RunConfigurationJson.encode(set0Advances),
                    "a 0 advance must not change the encoded document (cache identity)",
                )
                assertNull(base.scenarios.first().runOverrides?.numberOfStreamAdvancesPriorToRunning)

                // replicationSet=1 -> 4 advances -> present in the overrides; distinct document.
                val set1 = service.singleRunConfig("MM1", numberOfReplications = 4, streamAdvances = RunService.streamAdvancesFor(1, 4))
                assertEquals(4, set1.scenarios.first().runOverrides?.numberOfStreamAdvancesPriorToRunning)
                assertTrue(
                    RunConfigurationJson.encode(base) != RunConfigurationJson.encode(set1),
                    "an independent set must change the encoded document",
                )

                // antithetic is carried through.
                val anti = service.singleRunConfig("MM1", numberOfReplications = 4, antithetic = true)
                assertEquals(true, anti.scenarios.first().runOverrides?.antitheticOption)
            }
        } finally {
            registry.close()
        }
    }
}
