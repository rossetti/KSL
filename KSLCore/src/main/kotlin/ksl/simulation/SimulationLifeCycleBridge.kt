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

package ksl.simulation

import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import ksl.controls.ControlIfc
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.FrequencyResponseCIfc
import ksl.modeling.variable.HistogramResponseCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TimeSeriesResponseCIfc
import ksl.observers.ModelElementObserver
import ksl.utilities.IdentityIfc
import ksl.utilities.io.dbutil.AcrossRepStatTableData
import ksl.utilities.io.dbutil.BatchStatTableData
import ksl.utilities.io.dbutil.ControlTableData
import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.FrequencyTableData
import ksl.utilities.io.dbutil.HistogramTableData
import ksl.utilities.io.dbutil.ModelElementTableData
import ksl.utilities.io.dbutil.RvParameterTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.io.dbutil.TimeSeriesResponseTableData
import ksl.utilities.io.dbutil.WithinRepCounterStatTableData
import ksl.utilities.io.dbutil.WithinRepStatTableData
import ksl.utilities.random.rvariable.parameters.RVParameterData
import ksl.utilities.statistic.BatchStatisticIfc
import ksl.utilities.statistic.StatisticIfc
import java.sql.Timestamp

/**
 * Observes the simulation lifecycle and emits immutable [SimulationSnapshot] instances
 * through the provided [SimulationLifeCycleEmitters].
 *
 * The extraction logic mirrors the private methods in `KSLDatabase` exactly.
 * `KSLDatabase` and its sequential write path are completely unchanged.
 * Database foreign-key fields (`exp_id`, `run_id`) are set to -1 as placeholders;
 * `SnapshotBatchWriter` remaps them to real auto-increment IDs at commit time.
 *
 * Instantiated and attached to the model lazily via [Model.lifeCycleEmitters].
 */
internal class SimulationLifeCycleBridge(
    private val model: Model,
    private val emitters: SimulationLifeCycleEmitters
) : ModelElementObserver() {

    private var myCurrentExpRecord: ExperimentTableData? = null
    private var myCurrentRunRecord: SimulationRunTableData? = null

    override fun beforeExperiment(modelElement: ModelElement) {
        myCurrentExpRecord = null
        myCurrentRunRecord = null

        // State must be captured whenever any downstream emitter has subscribers,
        // not just when experimentStarted itself is observed.
        val anyObserved = emitters.experimentStarted.isObserved ||
                emitters.replicationCompleted.isObserved ||
                emitters.experimentCompleted.isObserved
        if (!anyObserved) return

        val expRecord = extractExperimentData(model)
        val runRecord = extractSimulationRunData(model)
        myCurrentExpRecord = expRecord
        myCurrentRunRecord = runRecord

        if (!emitters.experimentStarted.isObserved) return

        val modelElements = model.getModelElements().map { extractModelElement(it) }

        val controls: List<ControlTableData> = if (model.hasExperimentalControls()) {
            model.controls().asList().map { extractControlRecord(it) }
        } else emptyList()

        val rvParameters: List<RvParameterTableData> = if (model.hasParameterSetter()) {
            model.rvParameterSetter.rvParametersData.map { extractRvParameterRecord(it) }
        } else emptyList()

        emitters.experimentStarted.emit(
            SimulationSnapshot.ExperimentStarted(
                experiment = expRecord,
                simulationRun = runRecord,
                modelElements = modelElements,
                controls = controls,
                rvParameters = rvParameters
            )
        )
    }

    override fun afterReplication(modelElement: ModelElement) {
        if (!emitters.replicationCompleted.isObserved) return
        val runRecord = myCurrentRunRecord ?: return
        val repId = model.currentReplicationId
        val runId = runRecord.run_id

        val withinRepStats = model.responses.map { extractWithinRepStat(repId, it, runId) }
        val withinRepCounterStats = model.counters.map { extractWithinRepCounter(repId, it, runId) }

        val batchStats = mutableListOf<BatchStatTableData>()
        model.batchingElement?.let { batcher ->
            batcher.allResponseBatchStatisticsAsMap.forEach { (response, bs) ->
                batchStats.add(extractBatchStat(repId, response, runId, bs))
            }
            batcher.allTimeWeightedBatchStatisticsAsMap.forEach { (twResponse, bs) ->
                batchStats.add(extractBatchStat(repId, twResponse, runId, bs))
            }
        }

        emitters.replicationCompleted.emit(
            SimulationSnapshot.ReplicationCompleted(
                repId = repId,
                withinRepStats = withinRepStats,
                withinRepCounterStats = withinRepCounterStats,
                batchStats = batchStats
            )
        )
    }

    override fun afterExperiment(modelElement: ModelElement) {
        if (!emitters.experimentCompleted.isObserved) return
        val runRecord = myCurrentRunRecord ?: return
        val expRecord = myCurrentExpRecord ?: extractExperimentData(model)
        val runId = runRecord.run_id

        val finalizedRun = runRecord.copy(
            last_rep_id = model.startingRepId + model.numberReplicationsCompleted - 1,
            run_end_time_stamp = Timestamp.from(Clock.System.now().toJavaInstant()).time,
            run_error_msg = model.runErrorMsg
        )

        val acrossRepStats = mutableListOf<AcrossRepStatTableData>()
        model.responses.forEach { acrossRepStats.add(extractAcrossRepStat(it, runId, it.acrossReplicationStatistic)) }
        model.counters.forEach { acrossRepStats.add(extractAcrossRepStat(it, runId, it.acrossReplicationStatistic)) }

        val histograms = model.histograms.flatMap { extractHistogramData(it, runId) }
        val frequencies = model.frequencies.flatMap { extractFrequencyData(it, runId) }
        val timeSeries = model.timeSeriesResponses.flatMap { extractTimeSeriesData(it, runId) }

        emitters.experimentCompleted.emit(
            SimulationSnapshot.ExperimentCompleted(
                simulationRun = finalizedRun,
                acrossRepStats = acrossRepStats,
                histograms = histograms,
                frequencies = frequencies,
                timeSeries = timeSeries,
                experiment = expRecord
            )
        )

        myCurrentExpRecord = null
        myCurrentRunRecord = null
    }

    // -------------------------------------------------------------------------
    // Extraction functions — mirror KSLDatabase private create* methods exactly.
    // All database FK fields (exp_id, run_id) remain -1; SnapshotBatchWriter
    // remaps them to real auto-increment IDs at commit time.
    // -------------------------------------------------------------------------

    private fun extractExperimentData(model: Model): ExperimentTableData {
        val record = ExperimentTableData()
        record.sim_name = model.simulationName
        record.exp_name = model.experimentName
        record.model_name = model.name
        record.num_chunks = model.numChunks
        if (!model.lengthOfReplication.isNaN() && model.lengthOfReplication.isFinite()) {
            record.length_of_rep = model.lengthOfReplication
        }
        record.length_of_warm_up = model.lengthOfReplicationWarmUp
        record.rep_allowed_exec_time = model.maximumAllowedExecutionTime.inWholeMilliseconds
        record.rep_init_option = model.replicationInitializationOption
        record.reset_start_stream_option = model.resetStartStreamOption
        record.antithetic_option = model.antitheticOption
        record.adv_next_sub_stream_option = model.advanceNextSubStreamOption
        record.num_stream_advances = model.numberOfStreamAdvancesPriorToRunning
        record.gc_after_rep_option = model.garbageCollectAfterReplicationFlag
        return record
    }

    private fun extractSimulationRunData(model: Model): SimulationRunTableData {
        val record = SimulationRunTableData()
        record.num_reps = model.numberOfReplications
        record.run_name = model.runName
        record.start_rep_id = model.startingRepId
        record.run_start_time_stamp = Timestamp.from(Clock.System.now().toJavaInstant()).time
        return record
    }

    private fun extractModelElement(element: ModelElement): ModelElementTableData {
        val dbm = ModelElementTableData()
        dbm.element_name = element.name
        dbm.element_id = element.id
        dbm.class_name = element::class.simpleName!!
        if (element.myParentModelElement != null) {
            dbm.parent_id_fk = element.myParentModelElement!!.id
            dbm.parent_name = element.myParentModelElement!!.name
        }
        dbm.left_count = element.leftTraversalCount
        dbm.right_count = element.rightTraversalCount
        return dbm
    }

    private fun extractControlRecord(control: ControlIfc): ControlTableData {
        val c = ControlTableData()
        c.element_id_fk = control.elementId
        c.key_name = control.keyName
        if (!control.value.isNaN() && control.value.isFinite()) {
            c.control_value = control.value
        }
        if (!control.lowerBound.isNaN() && control.lowerBound.isFinite()) {
            c.lower_bound = control.lowerBound
        }
        if (!control.upperBound.isNaN() && control.upperBound.isFinite()) {
            c.upper_bound = control.upperBound
        }
        c.property_name = control.propertyName
        c.control_type = control.type.toString()
        c.comment = control.comment
        return c
    }

    private fun extractRvParameterRecord(rvParamData: RVParameterData): RvParameterTableData {
        val rvp = RvParameterTableData()
        rvp.element_id_fk = rvParamData.elementId
        rvp.class_name = rvParamData.clazzName
        rvp.data_type = rvParamData.dataType
        rvp.rv_name = rvParamData.rvName
        rvp.param_name = rvParamData.paramName
        rvp.param_value = rvParamData.paramValue
        return rvp
    }

    private fun extractWithinRepStat(repId: Int, response: ResponseCIfc, runId: Int): WithinRepStatTableData {
        val r = WithinRepStatTableData()
        r.element_id_fk = response.id
        r.sim_run_id_fk = runId
        r.rep_id = repId
        val s = response.withinReplicationStatistic
        r.stat_name = s.name
        if (!s.count.isNaN() && s.count.isFinite()) r.stat_count = s.count
        if (!s.weightedAverage.isNaN() && s.weightedAverage.isFinite()) r.average = s.weightedAverage
        if (!s.min.isNaN() && s.min.isFinite()) r.minimum = s.min
        if (!s.max.isNaN() && s.max.isFinite()) r.maximum = s.max
        if (!s.weightedSum.isNaN() && s.weightedSum.isFinite()) r.weighted_sum = s.weightedSum
        if (!s.sumOfWeights.isNaN() && s.sumOfWeights.isFinite()) r.sum_of_weights = s.sumOfWeights
        if (!s.weightedSumOfSquares.isNaN() && s.weightedSumOfSquares.isFinite()) r.weighted_ssq = s.weightedSumOfSquares
        if (!s.lastValue.isNaN() && s.lastValue.isFinite()) r.last_value = s.lastValue
        if (!s.lastWeight.isNaN() && s.lastWeight.isFinite()) r.last_weight = s.lastWeight
        return r
    }

    private fun extractWithinRepCounter(repId: Int, counter: CounterCIfc, runId: Int): WithinRepCounterStatTableData {
        val r = WithinRepCounterStatTableData()
        r.element_id_fk = counter.id
        r.sim_run_id_fk = runId
        r.rep_id = repId
        r.stat_name = counter.name
        if (!counter.value.isNaN() && counter.value.isFinite()) r.last_value = counter.value
        return r
    }

    private fun extractAcrossRepStat(response: IdentityIfc, runId: Int, s: StatisticIfc): AcrossRepStatTableData {
        val r = AcrossRepStatTableData()
        r.element_id_fk = response.id
        r.sim_run_id_fk = runId
        r.stat_name = s.name
        if (!s.count.isNaN() && s.count.isFinite()) r.stat_count = s.count
        if (!s.average.isNaN() && s.average.isFinite()) r.average = s.average
        if (!s.standardDeviation.isNaN() && s.standardDeviation.isFinite()) r.std_dev = s.standardDeviation
        if (!s.standardError.isNaN() && s.standardError.isFinite()) r.std_err = s.standardError
        if (!s.halfWidth.isNaN() && s.halfWidth.isFinite()) r.half_width = s.halfWidth
        if (!s.confidenceLevel.isNaN() && s.confidenceLevel.isFinite()) r.conf_level = s.confidenceLevel
        if (!s.min.isNaN() && s.min.isFinite()) r.minimum = s.min
        if (!s.max.isNaN() && s.max.isFinite()) r.maximum = s.max
        if (!s.sum.isNaN() && s.sum.isFinite()) r.sum_of_obs = s.sum
        if (!s.deviationSumOfSquares.isNaN() && s.deviationSumOfSquares.isFinite()) r.dev_ssq = s.deviationSumOfSquares
        if (!s.lastValue.isNaN() && s.lastValue.isFinite()) r.last_value = s.lastValue
        if (!s.kurtosis.isNaN() && s.kurtosis.isFinite()) r.kurtosis = s.kurtosis
        if (!s.skewness.isNaN() && s.skewness.isFinite()) r.skewness = s.skewness
        if (!s.lag1Covariance.isNaN() && s.lag1Covariance.isFinite()) r.lag1_cov = s.lag1Covariance
        if (!s.lag1Correlation.isNaN() && s.lag1Correlation.isFinite()) r.lag1_corr = s.lag1Correlation
        if (!s.vonNeumannLag1TestStatistic.isNaN() && s.vonNeumannLag1TestStatistic.isFinite()) r.von_neumann_lag1_stat = s.vonNeumannLag1TestStatistic
        if (!s.numberMissing.isNaN() && s.numberMissing.isFinite()) r.num_missing_obs = s.numberMissing
        return r
    }

    private fun extractBatchStat(repId: Int, response: ResponseCIfc, runId: Int, s: BatchStatisticIfc): BatchStatTableData {
        val r = BatchStatTableData()
        r.element_id_fk = response.id
        r.sim_run_id_fk = runId
        r.rep_id = repId
        r.stat_name = s.name
        if (!s.count.isNaN() && s.count.isFinite()) r.stat_count = s.count
        if (!s.average.isNaN() && s.average.isFinite()) r.average = s.average
        if (!s.standardDeviation.isNaN() && s.standardDeviation.isFinite()) r.std_dev = s.standardDeviation
        if (!s.standardError.isNaN() && s.standardError.isFinite()) r.std_err = s.standardError
        if (!s.halfWidth.isNaN() && s.halfWidth.isFinite()) r.half_width = s.halfWidth
        if (!s.confidenceLevel.isNaN() && s.confidenceLevel.isFinite()) r.conf_level = s.confidenceLevel
        if (!s.min.isNaN() && s.min.isFinite()) r.minimum = s.min
        if (!s.max.isNaN() && s.max.isFinite()) r.maximum = s.max
        if (!s.sum.isNaN() && s.sum.isFinite()) r.sum_of_obs = s.sum
        if (!s.deviationSumOfSquares.isNaN() && s.deviationSumOfSquares.isFinite()) r.dev_ssq = s.deviationSumOfSquares
        if (!s.lastValue.isNaN() && s.lastValue.isFinite()) r.last_value = s.lastValue
        if (!s.kurtosis.isNaN() && s.kurtosis.isFinite()) r.kurtosis = s.kurtosis
        if (!s.skewness.isNaN() && s.skewness.isFinite()) r.skewness = s.skewness
        if (!s.lag1Covariance.isNaN() && s.lag1Covariance.isFinite()) r.lag1_cov = s.lag1Covariance
        if (!s.lag1Correlation.isNaN() && s.lag1Correlation.isFinite()) r.lag1_corr = s.lag1Correlation
        if (!s.vonNeumannLag1TestStatistic.isNaN() && s.vonNeumannLag1TestStatistic.isFinite()) r.von_neumann_lag1_stat = s.vonNeumannLag1TestStatistic
        if (!s.numberMissing.isNaN() && s.numberMissing.isFinite()) r.num_missing_obs = s.numberMissing
        r.min_batch_size = s.minBatchSize.toDouble()
        r.min_num_batches = s.minNumBatches.toDouble()
        r.max_num_batches_multiple = s.minNumBatchesMultiple.toDouble()
        r.max_num_batches = s.maxNumBatches.toDouble()
        r.num_rebatches = s.numRebatches.toDouble()
        r.current_batch_size = s.currentBatchSize.toDouble()
        if (!s.amountLeftUnbatched.isNaN() && s.amountLeftUnbatched.isFinite()) r.amt_unbatched = s.amountLeftUnbatched
        if (!s.totalNumberOfObservations.isNaN() && s.totalNumberOfObservations.isFinite()) r.total_num_obs = s.totalNumberOfObservations
        return r
    }

    private fun extractHistogramData(histResponse: HistogramResponseCIfc, runId: Int): List<HistogramTableData> {
        val list = mutableListOf<HistogramTableData>()
        for (hd in histResponse.histogram.histogramData()) {
            val record = HistogramTableData()
            record.element_id_fk = histResponse.id
            record.sim_run_id_fk = runId
            record.response_id_fk = histResponse.response.id
            record.response_name = histResponse.response.name
            record.bin_label = hd.binLabel
            record.bin_num = hd.binNum
            if (!hd.binLowerLimit.isNaN() && hd.binLowerLimit.isFinite()) record.bin_lower_limit = hd.binLowerLimit
            if (!hd.binUpperLimit.isNaN() && hd.binUpperLimit.isFinite()) record.bin_upper_limit = hd.binUpperLimit
            if (!hd.binCount.isNaN() && hd.binCount.isFinite()) record.bin_count = hd.binCount
            if (!hd.cumCount.isNaN() && hd.cumCount.isFinite()) record.bin_cum_count = hd.cumCount
            if (!hd.proportion.isNaN() && hd.proportion.isFinite()) record.bin_proportion = hd.proportion
            if (!hd.cumProportion.isNaN() && hd.cumProportion.isFinite()) record.bin_cum_proportion = hd.cumProportion
            list.add(record)
        }
        return list
    }

    private fun extractFrequencyData(freq: FrequencyResponseCIfc, runId: Int): List<FrequencyTableData> {
        val list = mutableListOf<FrequencyTableData>()
        for (fd in freq.frequencyResponse.frequencyData()) {
            val record = FrequencyTableData()
            record.element_id_fk = freq.id
            record.sim_run_id_fk = runId
            record.name = freq.name
            record.cell_label = fd.cellLabel
            record.value = fd.value
            if (!fd.count.isNaN() && fd.count.isFinite()) record.count = fd.count
            if (!fd.cum_count.isNaN() && fd.cum_count.isFinite()) record.cum_count = fd.cum_count
            if (!fd.proportion.isNaN() && fd.proportion.isFinite()) record.proportion = fd.proportion
            if (!fd.cumProportion.isNaN() && fd.cumProportion.isFinite()) record.cum_proportion = fd.cumProportion
            list.add(record)
        }
        return list
    }

    private fun extractTimeSeriesData(response: TimeSeriesResponseCIfc, runId: Int): List<TimeSeriesResponseTableData> {
        val list = mutableListOf<TimeSeriesResponseTableData>()
        for (tsData in response.allTimeSeriesPeriodDataAsList()) {
            val record = TimeSeriesResponseTableData()
            record.element_id_fk = tsData.elementId
            record.sim_run_id_fk = runId
            record.rep_id = tsData.repNum
            record.stat_name = tsData.responseName
            record.period = tsData.period
            record.start_time = tsData.startTime
            record.end_time = tsData.endTime
            record.length = tsData.length
            record.value = tsData.value
            list.add(record)
        }
        return list
    }
}
