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

package ksl.service.preview

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.config.experiment.ControlBinding
import ksl.app.config.experiment.DesignSpec
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.experiment.FactorSpec
import ksl.app.config.experiment.ReplicationSpec
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the Tier C preview: the workload/cost figures are derived purely from
 * the document, and the canonical echo is present. The numbers are deterministic
 * (a 2^2 factorial is four points), so this pins the cost math.
 */
class DocumentPreviewTest {

    private fun workload(preview: JsonObject) = preview["workload"]!!.jsonObject

    @Test
    fun `run preview reports scenario count and replication budget`() {
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec("s1", ModelReference.ByProviderId("MM1"), runOverrides = ExperimentRunOverrides(numberOfReplications = 5)),
                ScenarioSpec("s2", ModelReference.ByProviderId("MM1")), // inherits model default
            ),
        )
        val preview = DocumentPreview.forRun(config)
        val w = workload(preview)
        assertEquals(2, w["scenarioCount"]!!.jsonPrimitive.int)
        assertEquals(5, w["totalReplicationsSpecified"]!!.jsonPrimitive.int)
        assertTrue(w["someInheritModelDefaults"]!!.jsonPrimitive.boolean)
        assertTrue(preview["canonical"] is JsonObject, "canonical echo should be a nested document")
    }

    @Test
    fun `experiment preview makes the 2 to the k design-point blow-up visible`() {
        val config = ExperimentConfiguration(
            modelReference = ModelReference.ByProviderId("MM1"),
            factors = listOf(
                FactorSpec("A", listOf(1.0, 2.0), ControlBinding.Control("A")),
                FactorSpec("B", listOf(1.0, 2.0), ControlBinding.Control("B")),
            ),
            designSpec = DesignSpec.TwoLevelFactorial(),
            replications = ReplicationSpec.Uniform(10),
        )
        val w = workload(DocumentPreview.forExperiment(config))
        assertEquals(2, w["factorCount"]!!.jsonPrimitive.int)
        assertEquals(4, w["designPointCount"]!!.jsonPrimitive.int, "2^2 = four design points")
        assertEquals(40L, w["totalReplications"]!!.jsonPrimitive.long, "4 points x 10 reps")
    }

    @Test
    fun `fit preview reports dataset and estimator counts`() {
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf("a" to doubleArrayOf(1.0, 2.0), "b" to doubleArrayOf(3.0, 4.0))),
            kind = DistributionKind.CONTINUOUS,
            estimatorIds = setOf("est1"),
        )
        val w = workload(DocumentPreview.forFit(config))
        assertEquals(2, w["datasetCount"]!!.jsonPrimitive.int)
        assertEquals(1, w["estimatorCount"]!!.jsonPrimitive.int)
        assertEquals(false, w["usesCatalogDefaultEstimators"]!!.jsonPrimitive.boolean)
        assertEquals("CONTINUOUS", w["kind"]!!.jsonPrimitive.content)
    }
}
