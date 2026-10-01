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

package ksl.app.swing.animation.app

import ksl.animation.CaptureWindow
import ksl.app.config.ExperimentRunOverrides
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Animation app's two guards against a run that cannot be animated: it opens a study model at one
 * replication, since only one is animated, and it can measure how much trace a run would write before writing
 * it.
 */
class AnimationRunGuardTest {

    /** A shop built for a study: ten replications of a long run. */
    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
            Model("GuardShop").also {
                TestAndRepairShopWithMovableResources(it, "TR")
                it.numberOfReplications = 10
                it.lengthOfReplication = 2_000.0
            }
    }

    private fun <T> withController(block: (AnimationAppController) -> T): T {
        val controller = AnimationAppController("GuardShop", builder)
        try {
            return block(controller)
        } finally {
            controller.close()
        }
    }

    @Test
    fun aStudyModelOpensAtOneReplicationButAStatedCountIsKept() = withController { c ->
        assertEquals(1, c.runOverrides.value.numberOfReplications, "only one replication is animated")
        assertTrue(!c.isDirty.value, "and opening the model is not an edit")
        val config = c.currentConfiguration().let { cfg ->
            cfg.copy(scenarios = cfg.scenarios.map { it.copy(runOverrides = ExperimentRunOverrides(numberOfReplications = 5)) })
        }
        c.loadConfiguration(config)
        assertEquals(5, c.runOverrides.value.numberOfReplications, "a configuration that states a count keeps it")
    }

    @Test
    fun theEstimateScalesWithTheRunAndTheCaptureWindow() = withController { c ->
        val full = assertNotNull(c.estimateTrace())
        assertTrue(full.bytes > 0, "the shop writes a trace")
        assertEquals(2_000.0, full.capturedSpan)

        c.updateRunOverride { it.copy(lengthOfReplication = 4_000.0) }
        val doubled = assertNotNull(c.estimateTrace())
        val ratio = doubled.bytes.toDouble() / full.bytes
        assertTrue(ratio in 1.6..2.4, "twice the run is about twice the trace, got $ratio")

        c.setCaptureWindow(1_000.0, 2_000.0)
        val windowed = assertNotNull(c.estimateTrace())
        assertTrue(windowed.windowed)
        assertEquals(1_000.0, windowed.capturedSpan)
        val share = windowed.bytes.toDouble() / doubled.bytes
        assertTrue(share in 0.2..0.3, "a quarter of the run's time is about a quarter of the trace, got $share")

        assertEquals(1_000.0, full.lengthFor(full.bytes / 2), 1.0, "shorten-to-fit halves the run to halve the trace")
        val offered = AnimationAppController.TraceEstimate(3_000_000_000L, 249_600.0, 249_600.0, false)
            .roundLengthFor(AnimationAppController.FIT_TRACE_BYTES)
        assertEquals(2_100.0, offered, "offered as a length someone would choose, rounded down: 2,181 reads 2,100")
    }
}
