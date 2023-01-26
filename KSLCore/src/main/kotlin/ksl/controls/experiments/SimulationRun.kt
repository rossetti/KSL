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

package ksl.controls.experiments

import kotlinx.datetime.Instant
import ksl.simulation.Experiment
import ksl.utilities.io.KSL
import ksl.utilities.io.StatisticReporter
import ksl.utilities.statistic.Statistic

/**
 * A SimulationRun represents the execution of a simulation with inputs (controls and parameters),
 * and output (results).  A run consists of a number of replications that were executed with the
 * same inputs and parameters, which cause the creation of results for each response within
 * each replication. The main purpose of SimulationRun is to transfer data about the execution
 * of a simulation. It acts as a data transfer class.
 */
@kotlinx.serialization.Serializable
class SimulationRun private constructor(
    val experimentRunParameters: ExperimentRunParameters,
    val id: String,
    var name: String,
    var functionError: String = "",
    var inputs: Map<String, Double> = mapOf(),
    var results: Map<String, DoubleArray> = mapOf(),
    var beginExecutionTime: Instant = Instant.DISTANT_PAST,
    var endExecutionTime: Instant = Instant.DISTANT_FUTURE
) {
    constructor(
        experiment: ExperimentRunParameters,
        inputs: Map<String, Double> = mapOf(),
        runId: String? = null,
        runName: String? = null
    ) : this(experiment,
        runId ?: KSL.randomUUIDString(),
        runName ?: ("ID_$runId"),
        inputs = inputs
    )

    /** Use primarily for printing out run results
     *
     * @return a StatisticReporter with the summary statistics of the run
     */
    fun statisticalReporter(): StatisticReporter {
        val r = StatisticReporter()
        for ((key, value) in results.entries) {
            r.addStatistic(Statistic(key, value))
        }
        return r
    }
}