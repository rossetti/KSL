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
import kotlinx.serialization.json.Json
import ksl.utilities.io.KSL
import ksl.utilities.io.StatisticReporter
import ksl.utilities.io.ToJSONIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import kotlin.String
import kotlin.collections.Map

/**
 * A SimulationRun represents the execution of a simulation with inputs (controls and parameters),
 * and output (results).  A run consists of a number of replications that were executed with the
 * same inputs and parameters, which cause the creation of results for each response within
 * each replication. The main purpose of SimulationRun is to transfer data about the execution
 * of a simulation. It acts as a data transfer class.
 *
 * After the simulation run is executed, the 'results' property will hold pairs (response name, array).
 * The response name is the name of the model element associated with the response, and
 * the array contains the observations of the response for each replication.
 */
@kotlinx.serialization.Serializable
class SimulationRun private constructor(
    val id: String,
    val modelIdentifier: String,
    var name: String,
    val experimentRunParameters: ExperimentRunParameters,
    var runErrorMsg: String = "",
    var beginExecutionTime: Instant = Instant.DISTANT_PAST,
    var endExecutionTime: Instant = Instant.DISTANT_FUTURE,
    var inputs: Map<String, Double> = mapOf(),
    var modelConfiguration: Map<String, String>? = null,
    var results: Map<String, DoubleArray> = mapOf()
) : ToJSONIfc {

    @JvmOverloads
    constructor(
        modelIdentifier: String,
        experimentRunParameters: ExperimentRunParameters,
        inputs: Map<String, Double> = mapOf(),
        runId: String? = null,
        runName: String? = null,
        modelConfiguration: Map<String, String>? = null
    ) : this(
        id = runId ?: KSL.randomUUIDString(),
        modelIdentifier = modelIdentifier,
        name = runName ?: (experimentRunParameters.experimentName),
        experimentRunParameters = experimentRunParameters,
        inputs = inputs,
        modelConfiguration = modelConfiguration
    )

    val numberOfReplications: Int
        get() = experimentRunParameters.numberOfReplications

    /**
     * The names of the model responses recorded in [results], excluding the internal
     * bookkeeping keys `"repNumbers"` and `"repTimings"`.
     */
    val responseNames: List<String>
        get() = results.keys.filter { it != "repNumbers" && it != "repTimings" }

    /**
     * `true` if an error occurred during execution (i.e. [runErrorMsg] is non-empty).
     */
    val hasError: Boolean
        get() = runErrorMsg.isNotEmpty()

    /**
     * `true` if the run produced results (i.e. [results] is non-empty).
     * Will be `false` after a fatal run error.
     */
    val hasResults: Boolean
        get() = results.isNotEmpty()

    /**
     * The number of model responses recorded in [results], excluding the internal
     * bookkeeping keys `"repNumbers"` and `"repTimings"`.
     * Equivalent to `responseNames.size`.
     */
    val responseCount: Int
        get() = responseNames.size

    /**
     * `true` if the run has been executed (i.e. [beginExecutionTime] has been set
     * from its initial sentinel value of [Instant.DISTANT_PAST]).
     */
    val hasBeenExecuted: Boolean
        get() = beginExecutionTime != Instant.DISTANT_PAST

    /**
     * Returns the per-replication wall-clock execution times in milliseconds as recorded
     * by [ksl.observers.SimulationTimer], or `null` if the run has not been executed.
     *
     * @return a [DoubleArray] with one timing value per replication, or `null`
     */
    val replicationTimings: DoubleArray?
        get() = results["repTimings"]

    /**
     * Returns the replication identifiers used during execution, or `null` if the run
     * has not been executed.
     *
     * The internal store holds replication numbers as doubles (produced by
     * [ksl.utilities.KSLArrays.toDoubles]); this property converts them to [IntArray].
     *
     * @return an [IntArray] of replication numbers in execution order, or `null`
     */
    val replicationNumbers: IntArray?
        get() = results["repNumbers"]?.let { arr -> IntArray(arr.size) { arr[it].toInt() } }

    /**
     * Returns the per-replication observations for [responseName], or `null` if the
     * name is absent from [results] or is one of the internal bookkeeping keys
     * (`"repNumbers"`, `"repTimings"`).
     *
     * @param responseName the response name to look up
     * @return a [DoubleArray] with one element per replication, or `null`
     */
    fun replicationObservations(responseName: String): DoubleArray? {
        if (responseName == "repNumbers" || responseName == "repTimings") return null
        return results[responseName]
    }

    /**
     * Returns a map from each response name to its across-replication [StatisticIfc],
     * computed from the per-replication observations in [results].
     *
     * The internal bookkeeping keys `"repNumbers"` and `"repTimings"` are excluded.
     * Insertion order matches [results].
     *
     * @param confidenceLevel the confidence level applied to each [Statistic];
     *                        defaults to 0.95
     * @return a [LinkedHashMap] of response name → [StatisticIfc]
     */
    fun acrossReplicationStatistics(confidenceLevel: Double = 0.95): Map<String, StatisticIfc> {
        val map = linkedMapOf<String, StatisticIfc>()
        for (name in responseNames) {
            val obs = results[name] ?: continue
            val s = Statistic(name, obs)
            s.confidenceLevel = confidenceLevel
            map[name] = s
        }
        return map
    }

    /**
     * Returns the across-replication [StatisticIfc] for a single [responseName],
     * or `null` when [responseName] is absent or is an internal bookkeeping key.
     *
     * @param responseName    the response to look up
     * @param confidenceLevel the confidence level for the returned [Statistic];
     *                        defaults to 0.95
     * @return a [Statistic] built from all replication observations, or `null`
     */
    fun acrossReplicationStatistic(
        responseName: String,
        confidenceLevel: Double = 0.95
    ): StatisticIfc? {
        val obs = replicationObservations(responseName) ?: return null
        return Statistic(responseName, obs).also { it.confidenceLevel = confidenceLevel }
    }

    /**
     * Returns a [StatisticReporter] containing the across-replication summary
     * statistics for every response in this run.
     *
     * Delegates to [acrossReplicationStatistics] so that the internal bookkeeping
     * keys `"repNumbers"` and `"repTimings"` are automatically excluded.
     *
     * @param confidenceLevel the confidence level applied to each [Statistic];
     *                        defaults to 0.95
     * @return a [StatisticReporter] with the summary statistics of the run
     */
    fun statisticalReporter(confidenceLevel: Double = 0.95): StatisticReporter {
        val r = StatisticReporter()
        for ((_, stat) in acrossReplicationStatistics(confidenceLevel)) {
            r.addStatistic(stat)
        }
        return r
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("id = $id")
        sb.appendLine("name = $name")
        sb.appendLine("model identifier = $modelIdentifier")
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
        sb.appendLine("Model Configuration:")
        if (modelConfiguration != null){
            if (modelConfiguration!!.isEmpty()){
                sb.appendLine("\t {empty}")
            } else {
                for ((key, value) in modelConfiguration){
                    sb.appendLine("key = $key")
                    sb.appendLine("value = $value")
                }
            }
        } else {
            sb.appendLine("\t None")
        }
        sb.appendLine("Results:")
        if (results.isEmpty()){
            sb.appendLine("\t {empty}")
        } else {
            for((key, value) in results){
                sb.appendLine("key = $key")
                sb.appendLine("value = ${value.joinToString(prefix = "[", postfix = "]", separator = ","  )}")
            }
        }
        return sb.toString()
    }

    override fun toJson(): String {
        val format = Json { prettyPrint = true }
        return format.encodeToString(this)
    }
}