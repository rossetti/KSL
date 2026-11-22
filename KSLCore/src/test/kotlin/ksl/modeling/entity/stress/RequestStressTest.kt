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

package ksl.modeling.entity.stress

import ksl.modeling.entity.ResourceInvariants
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 *  Randomized sweeps of requests for resources and pools (see `RequestStressModel`), with the library's
 *  resource invariant checks switched on.
 */
class RequestStressTest {

    private var myInvariantsWereEnabled = false

    @BeforeEach
    fun enableInvariants() {
        myInvariantsWereEnabled = ResourceInvariants.enabled
        ResourceInvariants.enabled = true
    }

    @AfterEach
    fun restoreInvariants() {
        ResourceInvariants.enabled = myInvariantsWereEnabled
    }

    /** Runs [seeds] and returns one line per kind of problem found: its seeds and the first seed's message. */
    private fun sweep(
        label: String, features: Set<String>, seeds: LongRange, rule: Boolean = false, n: Int = 14,
        little: Boolean = true, variant: Int = 0, warmUp: Double = 0.0, reps: Int = 1
    ): List<String> {
        val byMessage = LinkedHashMap<String, MutableList<Long>>()
        val firstMessage = HashMap<String, String>()
        for (seed in seeds) {
            val r = RequestStressModel.runOne(seed, features, rule, n, little, variant, warmUp, reps) ?: continue
            val key = r.replace(Regex("[0-9]+(\\.[0-9]+)?"), "#").take(160)
            byMessage.getOrPut(key) { mutableListOf() }.add(seed)
            firstMessage.putIfAbsent(key, "seed=$seed: $r")
        }
        return byMessage.map { (k, v) -> "sweep $label: ${v.size} seeds ${v.take(8)}, first ${firstMessage[k]}" }
    }

    /**
     *  No exception, every job finishes or is terminated, the units held agree with NumBusyUnits and
     *  SeizeCount, each queue's NumInQ integral equals its recorded waits where every leaving is recorded, a
     *  pool's NumBusy is the sum of its members', and two replications repeat.
     */
    @Test
    @DisplayName("Random requests for resources and pools, with moves, terminations and disturbances, keep their accounting")
    fun randomRequests() {
        val failures = mutableListOf<String>()
        failures += sweep("plain", setOf("pool", "timed", "two"), 1L..150L, n = 24)
        failures += sweep("moves", setOf("pool", "timed", "move", "two"), 1L..200L, n = 30)
        failures += sweep("moves-rule", setOf("pool", "timed", "move", "two"), 1L..200L, n = 30, rule = true)
        failures += sweep("term-move", setOf("pool", "timed", "term", "move", "nowff", "cap", "fail", "shift"), 1L..200L, n = 30)
        failures += sweep("all", ALL, 1L..300L, n = 24, little = false)
        failures += sweep("all-rule", ALL, 1L..150L, n = 24, little = false, rule = true)
        failures += sweep("all-warm", ALL, 1L..150L, n = 24, little = false, warmUp = 12.0)
        for (v in listOf(1, 2, 3, 4, 5, 6)) {
            failures += sweep("all-variant$v", ALL, 1L..150L, n = 24, little = false, variant = v)
        }
        for (v in listOf(0, 1, 3, 5, 6)) {
            failures += sweep("two-replications-variant$v", ALL, 1L..100L, n = 24, little = false, variant = v, reps = 2)
        }
        assertTrue(failures.isEmpty(), "the randomized sweeps found failures:\n" + failures.joinToString("\n"))
    }

    private companion object {
        val ALL = setOf("pool", "timed", "move", "term", "cap", "shift", "fail", "two", "preempt", "inact")
    }
}
