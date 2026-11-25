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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.fail

/**
 *  Applies every disruption at every point of every same-instant cluster of the base scenarios, under each
 *  capacity-change rule, interrupt rule and with and without a request selection rule (see [SameInstant]).
 */
class SameInstantExplorationTest {

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

    private fun report(e: SameInstant.Exploration) {
        val report = StringBuilder("same-instant exploration: ${e.runs} runs, ${e.applied} with the disruption applied, ${e.findings.size} kinds of finding")
        for (f in e.findings.sortedByDescending { it.count }) {
            report.append("\n- [${f.count}] ${f.key}\n    smallest: ${f.first}")
            report.append("\n    log: " + f.log.takeLast(25).joinToString("\n         "))
        }
        println(report)
        if (e.findings.isNotEmpty()) fail(report.toString())
    }

    @Test
    @DisplayName("Every disruption at every point of a same-instant cluster leaves a consistent, live model")
    fun everyDisruptionAtEveryPoint() {
        report(SameInstant.explore())
    }

    /**
     *  Every disruption followed at once by one of a set of others, at every point: some minutes on one core,
     *  so it runs only when the environment variable `KSL_SAME_INSTANT_PAIRS` is `true`.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "KSL_SAME_INSTANT_PAIRS", matches = "true")
    @DisplayName("Every pair of disruptions at every point of a same-instant cluster leaves a consistent, live model")
    fun everyPairAtEveryPoint() {
        report(SameInstant.explore(pairs = true))
    }
}
