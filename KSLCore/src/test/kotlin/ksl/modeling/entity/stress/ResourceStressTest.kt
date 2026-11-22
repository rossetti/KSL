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
 *  Randomized sweeps of one resource with a queue under seizes, preemptions, failures, shifts, capacity
 *  changes, declared failures, capacity schedules and terminations, with the library's resource invariant
 *  checks switched on. Each seed must run without an exception and pass the model's end-of-replication
 *  checks (see `ResourceStressModel`).
 */
class ResourceStressTest {

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
     *  Runs the scenarios of seeds [first] to [last] and fails listing each kind of problem found, with its
     *  seeds and, for the smallest scenario showing it, the scenario, the problem and the event log.
     */
    private fun sweep(label: String, first: Int, last: Int, make: (Int) -> ResourceStress.Scenario) {
        val failures = LinkedHashMap<String, MutableList<Int>>()
        for (seed in first..last) {
            val (problem, _) = ResourceStress.run(make(seed))
            if (problem != null) {
                val key = problem.replace(Regex("[0-9]+(\\.[0-9]+)?"), "#").take(160)
                failures.getOrPut(key) { mutableListOf() }.add(seed)
            }
        }
        if (failures.isEmpty()) return
        val report = StringBuilder("sweep $label: ${failures.size} kinds of problem in seeds $first..$last")
        for ((k, seeds) in failures) {
            report.append("\n- ${seeds.size} seeds, first ${seeds.take(8)}: $k")
            val s = make(seeds.minByOrNull { make(it).jobs.size + make(it).disturbs.size }!!)
            val (problem, log) = ResourceStress.run(s)
            report.append("\n  scenario $s")
            report.append("\n  problem $problem")
            report.append("\n  log " + log.joinToString("\n      "))
        }
        fail(report.toString())
    }

    @Test
    @DisplayName("Random seizes, preemptions, failures, shifts and capacity notices leave the resource consistent")
    fun randomScenarios() {
        sweep("plain", 1, 4000) { ResourceStress.scenario(it) }
    }

    @Test
    @DisplayName("Random scenarios with declared failure processes leave the resource consistent")
    fun randomScenariosWithDeclaredFailures() {
        sweep("declared", 1, 4000) { ResourceStress.scenario(it, withDeclared = true) }
    }

    @Test
    @DisplayName("Random scenarios without restarts keep each job's work time plus its interruptions")
    fun randomScenariosWithoutRestarts() {
        sweep("resumeOnly", 1, 3000) { ResourceStress.scenario(it, allowRestart = false, withDeclared = true) }
    }

    @Test
    @DisplayName("Random scenarios with terminations and work done without the resource leave it consistent")
    fun randomScenariosWithTerminations() {
        sweep("extras", 1, 4000) { ResourceStress.scenario(it, withDeclared = true, extras = true) }
    }

    @Test
    @DisplayName("Random scenarios under a repeating capacity schedule leave the resource consistent")
    fun randomScenariosUnderACapacitySchedule() {
        sweep("schedule", 1, 4000) {
            ResourceStress.scenario(it, withDeclared = true, extras = it % 2 == 0, withSchedule = true)
        }
    }
}
