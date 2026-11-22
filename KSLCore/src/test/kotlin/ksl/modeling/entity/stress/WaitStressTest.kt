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
import ksl.simulation.Model
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 *  Randomized sweeps of waits for the first of several things (see `WaitStressModel`), two replications per
 *  seed, with the library's resource invariant checks switched on. Each seed must run without an exception
 *  and without a problem recorded by the model's checks.
 */
class WaitStressTest {

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

    /**
     *  Runs [seeds] with [features], stopping at the sixth failing seed, and fails describing each failing
     *  seed; the first ones carry the model's recent event log, and an exception's first stack frames.
     */
    private fun stress(title: String, features: Set<String>, seeds: LongRange) {
        val failures = mutableListOf<String>()
        for (seed in seeds) {
            val model = Model("WS${System.nanoTime()}", autoCSVReports = false)
            val s = WaitStressModel(model, seed, features)
            model.numberOfReplications = 2
            model.lengthOfReplication = 40.0
            try {
                model.simulate()
                if (s.problems.isNotEmpty()) {
                    val sb = StringBuilder("seed=$seed problems: ${s.problems.take(3)}")
                    if (failures.size < 2) s.log.forEach { sb.append("\n      $it") }
                    failures.add(sb.toString())
                }
            } catch (e: Throwable) {
                var root: Throwable = e
                while (root.cause != null) root = root.cause!!
                val sb = StringBuilder("seed=$seed threw ${root::class.simpleName}: ${root.message?.take(500)}")
                if (failures.size < 3) {
                    root.stackTrace.take(14).forEach { sb.append("\n        at $it") }
                    s.log.forEach { sb.append("\n      $it") }
                }
                failures.add(sb.toString())
            }
            if (failures.size >= 6) break
        }
        assertTrue(
            failures.isEmpty(),
            "waits sweep $title: ${failures.size} failing seeds (stops at 6) of ${seeds.count()}\n" + failures.joinToString("\n")
        )
    }

    @Test
    @DisplayName("Random waits for the first of several things end correctly and conserve items")
    fun randomWaits() = stress("base", setOf(), 1L..150L)

    @Test
    @DisplayName("Random waits end correctly when waiting jobs are terminated")
    fun randomWaitsWithTerminations() = stress("term", setOf("term"), 1L..150L)

    @Test
    @DisplayName("Random waits end correctly under first-fillable, ranked and selection-rule queues")
    fun randomWaitsWithQueueRules() =
        stress("ff+ranked+rule+term", setOf("ff", "ranked", "rule", "term"), 1L..150L)

    @Test
    @DisplayName("Random waits end correctly under capacity cuts and a bounded blocking queue")
    fun randomWaitsWithCapacityCuts() = stress("cap+term", setOf("cap", "term", "smallbin"), 1L..150L)

    @Test
    @DisplayName("Random waits end correctly under failures, with holders watching for interruption")
    fun randomWaitsWithFailures() =
        stress("fail+resume+watch+term", setOf("fail", "resume", "watch", "term", "cap"), 1L..150L)

    @Test
    @DisplayName("Random waits end correctly with every disturbance and queue rule together")
    fun randomWaitsWithEverything() = stress(
        "all", setOf("fail", "resume", "watch", "term", "cap", "ff", "ranked", "rule", "smallbin"), 1L..150L
    )
}
