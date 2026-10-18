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

package ksl.controls.experiments

import kotlinx.coroutines.runBlocking
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Designed experiments with a central composite design: two continuous factors of a single-server queue, the
 *  mean time between arrivals (1.0 to 1.5) and the mean service time (0.5 to 0.6), with rotatable axial spacing.
 *  The design has four factorial points of two replications, four axial points of one and a center point of three;
 *  every point keeps the queue stable.
 */
class CentralCompositeDesignExperimentTest {

    private val modelName = "CCDQueue"
    private val tba = TwoLevelFactor("MeanTBA", 1.0, 1.5)
    private val st = TwoLevelFactor("MeanST", 0.5, 0.6)
    private val settings = mapOf<Factor, String>(tba to "$modelName:TBA.mean", st to "$modelName:ServiceTime.mean")

    private fun design() = CentralCompositeDesign(
        setOf(tba, st), numFactorialReps = 2, numAxialReps = 1, numCenterReps = 3,
        axialSpacing = CentralCompositeDesign.rotatableAxialSpacing(2, numFactorialReps = 2, numAxialReps = 1)
    )

    private fun buildModel(): Model {
        val model = Model(modelName, autoCSVReports = false)
        model.lengthOfReplication = 100.0
        GIGcQueue(model, numServers = 1, ad = ExponentialRV(1.25, 1), sd = ExponentialRV(0.55, 2), name = "MM1Q")
        return model
    }

    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
            buildModel()
    }

    @Test
    @DisplayName("The design points of a central composite design and the design they report")
    fun pointsReportTheirDesign() {
        val ccd = design()
        assertEquals(9, ccd.designPoints().size)
        assertTrue(ccd.designPoints().none { it.design === ccd }, "as found, the points report the internal design")
    }

    @Test
    @DisplayName("Simulating a central composite design with DesignedExperiment")
    fun sequentialExperiment() {
        val de = DesignedExperiment("CCDSequential", buildModel(), settings, design(), kslDb = null)
        val error = assertFailsWith<IllegalArgumentException> { de.simulateAll() }
        assertTrue(error.message!!.contains("not associated"), error.message)
    }

    @Test
    @DisplayName("Simulating a central composite design with ParallelDesignedExperiment")
    fun parallelExperiment() = runBlocking {
        val pde = ParallelDesignedExperiment("CCDParallel", builder, settings, design(), kslDb = null)
        val result = runCatching { pde.simulateAll() }
        val error = result.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && error.message!!.contains("not associated"), "as found: $error")
    }
}
