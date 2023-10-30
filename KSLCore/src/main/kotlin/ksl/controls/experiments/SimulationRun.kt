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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    val id: String,
    var name: String,
    val experimentRunParameters: ExperimentRunParameters,
    var runErrorMsg: String = "",
    var beginExecutionTime: Instant = Instant.DISTANT_PAST,
    var endExecutionTime: Instant = Instant.DISTANT_FUTURE,
    var inputs: Map<String, Double> = mapOf(),
    var results: Map<String, DoubleArray> = mapOf()
) {
    constructor(
        experiment: ExperimentRunParameters,
        inputs: Map<String, Double> = mapOf(),
        runId: String? = null,
        runName: String? = null
    ) : this(
        runId ?: KSL.randomUUIDString(),
        runName ?: (experiment.experimentName),
        experiment,
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

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("id = $id")
        sb.appendLine("name = $name")
        sb.appendLine(experimentRunParameters)
        sb.appendLine("functionError $runErrorMsg")
        sb.appendLine("beginExecutionTime = $beginExecutionTime")
        sb.appendLine("endExecutionTime = $endExecutionTime")
        sb.appendLine("Inputs:")
        if (inputs.isEmpty()){
            sb.appendLine("\t {empty}")
        } else {
            for((key, value) in inputs){
                sb.appendLine("key = $key")
                sb.appendLine("value = $value")
            }
        }
        sb.appendLine("Results:")
        if (results.isEmpty()){
            sb.appendLine("\t {empty}")
        } else {
            for((key, value) in results){
                sb.appendLine("key = $key")
                sb.appendLine("value = $value")
            }
        }
        return sb.toString()
    }

    fun toJson(): String{
        val format = Json { prettyPrint = true }
        return format.encodeToString(this)
    }
}