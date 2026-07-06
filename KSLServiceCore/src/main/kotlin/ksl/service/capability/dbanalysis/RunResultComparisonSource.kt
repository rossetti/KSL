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

package ksl.service.capability.dbanalysis

import ksl.app.comparison.ComparisonDataSourceIfc
import ksl.app.comparison.InMemoryComparisonSource
import ksl.app.comparison.ResponseCategory
import ksl.service.capability.run.dto.RunResultDto

/**
 * Rehydrates a retained, database-less batch result into a [ComparisonDataSourceIfc]
 * so the same `MultipleComparisonAnalyzer` the database path uses can run against it.
 * Each [RunResultDto.BatchCompleted] item becomes one experiment; its
 * `replicationObservations` (carried through the DTO by C1) become the per-replication
 * values. Category and model label are cosmetic — the analyzer keys on
 * (experiment, response) only.
 */
object RunResultComparisonSource {

    fun fromBatch(dto: RunResultDto.BatchCompleted, label: String = "run result"): ComparisonDataSourceIfc {
        val builder = InMemoryComparisonSource.builder(label)
        for (item in dto.items) {
            builder.experiment(item.itemName, model = dto.summary.orchestratorName) {
                item.replicationObservations.forEach { (responseName, values) ->
                    if (values.isNotEmpty()) {
                        response(responseName, ResponseCategory.OBSERVATION, values.toDoubleArray())
                    }
                }
            }
        }
        return builder.build()
    }
}
