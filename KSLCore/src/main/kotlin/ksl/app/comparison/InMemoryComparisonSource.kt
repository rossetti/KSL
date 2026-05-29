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

package ksl.app.comparison

/**
 *  Test fixture implementation of [ComparisonDataSourceIfc] backed
 *  by an in-memory map.  Used by the substrate's own tests and
 *  available to any host (Swing, web, CLI, headless) that wants to
 *  drive a comparison UI without spinning up a real run.
 *
 *  Construction is via the builder-style `Builder` helper so test
 *  setup reads naturally:
 *
 *  ```kotlin
 *  val source = InMemoryComparisonSource.builder("test")
 *      .experiment("S1", model = "MM1") {
 *          response("SystemTime", ResponseCategory.OBSERVATION, doubleArrayOf(1.0, 2.0))
 *          response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(0.5, 0.6))
 *      }
 *      .build()
 *  ```
 */
class InMemoryComparisonSource private constructor(
    override val sourceLabel: String,
    private val rows: List<ExperimentRow>,
    private val observations: Map<Pair<String, String>, DoubleArray>
) : ComparisonDataSourceIfc {

    override fun availableExperiments(): List<ExperimentRow> = rows

    override fun observations(experimentName: String, responseName: String): DoubleArray? =
        observations[experimentName to responseName]?.copyOf()

    /** DSL-friendly builder. */
    class Builder(private val sourceLabel: String) {
        private val rows = mutableListOf<ExperimentRow>()
        private val obs = mutableMapOf<Pair<String, String>, DoubleArray>()

        fun experiment(
            name: String,
            model: String,
            block: ExperimentScope.() -> Unit
        ): Builder {
            val scope = ExperimentScope(name, model)
            scope.block()
            rows.add(scope.toRow())
            obs.putAll(scope.observations())
            return this
        }

        fun build(): InMemoryComparisonSource =
            InMemoryComparisonSource(sourceLabel, rows.toList(), obs.toMap())
    }

    class ExperimentScope internal constructor(
        private val name: String,
        private val model: String
    ) {
        private val responses = mutableListOf<ResponseRow>()
        private val obs = mutableMapOf<Pair<String, String>, DoubleArray>()
        private var numReps: Int? = null

        fun response(name: String, category: ResponseCategory, values: DoubleArray) {
            responses.add(ResponseRow(name, category))
            obs[this.name to name] = values.copyOf()
            // Track the number of reps from whichever response was supplied
            // first; downstream consumers assert equality across responses.
            if (numReps == null) numReps = values.size
        }

        internal fun toRow(): ExperimentRow = ExperimentRow(
            name = name,
            modelIdentifier = model,
            numReplications = numReps ?: 0,
            responses = responses.toList()
        )

        internal fun observations(): Map<Pair<String, String>, DoubleArray> = obs
    }

    companion object {
        fun builder(sourceLabel: String): Builder = Builder(sourceLabel)
    }
}
