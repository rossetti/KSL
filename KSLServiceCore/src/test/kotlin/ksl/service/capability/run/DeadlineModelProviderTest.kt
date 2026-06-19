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

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelProviderIfc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the per-replication cap stamping that backs the job deadline (Phase 9
 * A5): the decorator fills the deadline into a freshly built model that declares
 * no cap, an explicit author cap always wins, and unrelated provider methods are
 * delegated untouched. (The substrate's *enforcement* of the cap is covered by
 * `IterativeProcessExecutionTimeTest`; the end-to-end job-deadline behavior — a
 * runaway run/experiment/optimization surfacing as `Cancelled` — by
 * `RunServiceDeadlineTest`.)
 */
class DeadlineModelProviderTest {

    /** A provider whose `provideModel` always returns the supplied [model] instance. */
    private class FakeProvider(private val model: Model) : ModelProviderIfc {
        override fun isModelProvided(modelIdentifier: String): Boolean = modelIdentifier == "m"
        override fun modelIdentifiers(): List<String> = listOf("m")
        override fun provideModel(
            modelIdentifier: String,
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?,
        ): Model = model
    }

    @Test
    fun `stamps the deadline as the per-replication cap when the model declares none`() {
        val model = Model("m", autoCSVReports = false)
        assertEquals(Duration.ZERO, model.maximumAllowedExecutionTimePerReplication, "precondition: no cap")
        val provider = DeadlineModelProvider(FakeProvider(model), 30.seconds)

        val provided = provider.provideModel("m")

        assertEquals(30.seconds, provided.maximumAllowedExecutionTimePerReplication)
    }

    @Test
    fun `does not overwrite a model's explicit cap`() {
        val model = Model("m", autoCSVReports = false)
        model.maximumAllowedExecutionTimePerReplication = 2.seconds
        val provider = DeadlineModelProvider(FakeProvider(model), 30.seconds)

        assertEquals(2.seconds, provider.provideModel("m").maximumAllowedExecutionTimePerReplication)
    }

    @Test
    fun `delegates unrelated provider methods to the wrapped provider`() {
        val provider = DeadlineModelProvider(FakeProvider(Model("m", autoCSVReports = false)), 5.seconds)
        assertEquals(listOf("m"), provider.modelIdentifiers())
        assertEquals(true, provider.isModelProvided("m"))
        assertEquals(false, provider.isModelProvided("other"))
    }

    @Test
    fun `rejects a non-positive deadline`() {
        assertFailsWith<IllegalArgumentException> {
            DeadlineModelProvider(FakeProvider(Model("m", autoCSVReports = false)), Duration.ZERO)
        }
    }
}
