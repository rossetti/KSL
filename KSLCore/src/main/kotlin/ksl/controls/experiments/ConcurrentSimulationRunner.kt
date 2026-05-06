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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ksl.observers.ReplicationDataCollector
import ksl.observers.SimulationTimer
import ksl.simulation.Model
import kotlin.coroutines.coroutineContext

/**
 * Coroutine-aware runner for one simulation model and one [SimulationRun].
 *
 * This class intentionally does not manage lists of scenarios or design points.
 * Higher-level concurrent components launch their own child coroutines and use
 * one [ConcurrentSimulationRunner] per fresh [Model].  Keeping this class
 * single-run focused lets it share [SimulationRunner]'s setup/result semantics
 * while adding only the cooperative cancellation behavior needed by concurrent
 * workflows.
 */
class ConcurrentSimulationRunner(
    private val model: Model
) {

    /**
     *  @param modelProvider a function that will create the model that will be executed
     */
    @Suppress("unused")
    constructor(modelProvider: () -> Model) : this(modelProvider())

    /**
     * Builds a [SimulationRun] and executes it cooperatively.
     *
     * The inputs have the same meaning as [SimulationRunner.simulate].  Cancellation
     * is checked before the experiment starts and between replications.
     */
    @Suppress("unused")
    suspend fun simulate(
        modelIdentifier: String = model.modelIdentifier,
        inputs: Map<String, Double> = mapOf(),
        stringInputs: Map<String, String> = mapOf(),
        jsonInputs: Map<String, String> = mapOf(),
        experimentRunParameters: ExperimentRunParameters = model.extractRunParameters()
    ): SimulationRun {
        val simulationRun = SimulationRun(modelIdentifier, experimentRunParameters, inputs, stringInputs, jsonInputs)
        simulate(simulationRun)
        return simulationRun
    }

    /**
     * Runs [simulationRun] using the public replication-step API on [Model].
     *
     * The current replication is allowed to finish once it starts.  A cancellation
     * request is observed before the next replication begins, then rethrown so
     * parent scopes see true coroutine cancellation instead of a failed simulation.
     */
    suspend fun simulate(simulationRun: SimulationRun): SimulationRun {
        var timer: SimulationTimer? = null
        var rdc: ReplicationDataCollector? = null
        var initialized = false
        var ended = false

        try {
            SimulationRunner.setupSimulation(model, simulationRun)
            timer = SimulationTimer(model)
            rdc = ReplicationDataCollector(model, true)

            if (model.autoCSVReports) {
                model.turnOnCSVStatisticalReports()
            } else {
                model.turnOffCSVStatisticalReports()
            }

            coroutineContext.ensureActive()
            Model.logger.info { "ConcurrentSimulationRunner: Running simulation: ${model.simulationName} " }
            model.initializeReplications()
            initialized = true

            while (model.hasNextReplication() && !model.isDone) {
                coroutineContext.ensureActive()
                model.runNextReplication()
            }

            model.endSimulation()
            ended = true
            if (model.autoPrintSummaryReport) {
                model.print()
            }

            Model.logger.info { "ConcurrentSimulationRunner: Simulation ${model.simulationName} ended, capturing results." }
            rdc.stopObserving()
            timer.stopObserving()
            SimulationRunner.captureResults(model, simulationRun, timer, rdc)
            rdc = null
            timer = null
            return simulationRun

        } catch (e: CancellationException) {
            /*
             * Cancellation is not a failed simulation.  If the experiment reached
             * initialization, end it best-effort so model lifecycle cleanup can run,
             * then rethrow for structured-concurrency propagation.
             */
            withContext(NonCancellable) {
                if (initialized && !ended) {
                    runCatching { model.endSimulation(e.message ?: "Cancelled") }
                }
            }
            throw e

        } catch (e: RuntimeException) {
            SimulationRunner.recordSimulationRunError(
                model = model,
                simulationRun = simulationRun,
                e = e,
                sourceName = "ConcurrentSimulationRunner"
            )
            throw e

        } finally {
            rdc?.stopObserving()
            timer?.stopObserving()
        }
    }
}
