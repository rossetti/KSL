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
import kotlin.test.fail

/**
 *  Runs the randomized resource scenarios with the reference allocator watching every offer of units, and fails
 *  listing each kind of difference between the reference and the library, with the smallest scenario showing it.
 */
class ReferenceAllocatorSweepTest {

    private var myInvariantsWereEnabled = false
    private var myObserverBefore: ResourceInvariants.OfferObserver? = null

    @BeforeEach
    fun setUp() {
        myInvariantsWereEnabled = ResourceInvariants.enabled
        ResourceInvariants.enabled = true
        myObserverBefore = ResourceInvariants.offerObserver
    }

    @AfterEach
    fun tearDown() {
        ResourceInvariants.offerObserver = myObserverBefore
        ResourceInvariants.enabled = myInvariantsWereEnabled
    }

    private class Run(val observer: ReferenceOfferObserver, val problem: String?, val log: List<String>)

    /** The variant of the reference in use; see `ReferenceAllocator.decide`. Set by the environment variable `KSL_REFALLOC_VARIANT`. */
    private val myVariant = System.getenv("KSL_REFALLOC_VARIANT") == "preemptorsAlsoQueued"

    private fun runWithReference(s: ResourceStress.Scenario): Run {
        val observer = ReferenceOfferObserver(myVariant)
        val before = ResourceInvariants.offerObserver
        ResourceInvariants.offerObserver = observer
        try {
            val (problem, log) = ResourceStress.run(s)
            return Run(observer, problem, log)
        } finally {
            ResourceInvariants.offerObserver = before
        }
    }

    private fun sweep(label: String, first: Int, last: Int, make: (Int) -> ResourceStress.Scenario) {
        val kinds = LinkedHashMap<String, MutableList<Int>>()
        val counts = mutableMapOf<String, Int>()
        val problems = LinkedHashMap<String, MutableList<Int>>()
        var judged = 0
        val skipped = mutableMapOf<String, Int>()
        for (seed in first..last) {
            val run = runWithReference(make(seed))
            judged += run.observer.offersJudged
            run.observer.skipped.forEach { (k, v) -> skipped.merge(k, v, Int::plus) }
            run.problem?.let { p ->
                problems.getOrPut(p.replace(Regex("[0-9]+(\\.[0-9]+)?"), "#").take(160)) { mutableListOf() }.add(seed)
            }
            for (k in run.observer.differences.map { it.kind }.toSet()) kinds.getOrPut(k) { mutableListOf() }.add(seed)
            for (d in run.observer.differences) counts.merge(d.kind, 1, Int::plus)
        }
        val report = StringBuilder(
            "reference sweep $label${if (myVariant) " (variant: preemptors also queued)" else ""}, seeds $first..$last: $judged offers judged, skipped $skipped, " +
                    "${kinds.size} kinds of difference, ${problems.size} kinds of model problem"
        )
        for ((k, seeds) in kinds.entries.sortedByDescending { it.value.size }) {
            report.append("\n- $k: ${seeds.size} seeds, ${counts[k]} offers, first ${seeds.take(8)}")
            val smallest = seeds.minByOrNull { make(it).jobs.size + make(it).disturbs.size }!!
            val s = make(smallest)
            val run = runWithReference(s)
            report.append("\n  smallest: scenario $s")
            run.observer.differences.filter { it.kind == k }.take(2).forEach { report.append("\n  difference $it") }
            report.append("\n  log " + run.log.joinToString("\n      "))
        }
        for ((p, seeds) in problems) report.append("\n- model problem in ${seeds.size} seeds, first ${seeds.take(8)}: $p")
        println(report)
        if (kinds.isNotEmpty() || problems.isNotEmpty()) fail(report.toString())
    }

    @Test
    @DisplayName("Random seizes, preemptions, failures, shifts and capacity notices: every offer serves whom the rules say")
    fun randomScenarios() {
        sweep("plain", 1, 2000) { ResourceStress.scenario(it) }
    }

    @Test
    @DisplayName("Random scenarios with declared failure processes: every offer serves whom the rules say")
    fun randomScenariosWithDeclaredFailures() {
        sweep("declared", 1, 2000) { ResourceStress.scenario(it, withDeclared = true) }
    }

    @Test
    @DisplayName("Random scenarios with terminations and work without the resource: every offer serves whom the rules say")
    fun randomScenariosWithTerminations() {
        sweep("extras", 1, 2000) { ResourceStress.scenario(it, withDeclared = true, extras = true) }
    }

    @Test
    @DisplayName("Random scenarios under a repeating capacity schedule: every offer serves whom the rules say")
    fun randomScenariosUnderACapacitySchedule() {
        sweep("schedule", 1, 2000) {
            ResourceStress.scenario(it, withDeclared = true, extras = it % 2 == 0, withSchedule = true)
        }
    }
}
