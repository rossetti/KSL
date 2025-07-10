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
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.simulation

import kotlin.time.Duration

private var myCounter_: Int = 0

/**
 * This class provides the information for running a simulation experiment. An
 * experiment is a specification for the number of replications, the warm-up
 * length, replication length, etc. for controlling the running of a simulation.
 *
 * The defaults include:
 * - length of replication = Double.POSITIVE_INFINITY
 *
 * - length of warm up = 0.0
 *
 * - replication initialization TRUE - The system state is re-initialized prior to each replication
 *
 * - reset start stream option FALSE - Do not reset the streams of the random variables to their
 * starting points prior to running the replications within the experiment. This
 * implies that if the experiment is re-run on the same model in the same code
 * invocation that an independent set of replications will be made.
 *
 * - advance next sub-stream option TRUE - The random variables in a within an experiment
 * will start at the next sub-stream for each new replication
 *
 * - number of times to advance streams = 1 This indicates how many times that the streams should
 * be advanced prior to running the experiment. This can be used to ensure
 * simulations start with different streams
 *
 * - antithetic replication option is off by default
 *
 * Constructs an experiment called "name"
 *
 *  @param startingRepId the starting identifier in the sequence of identifiers used to identify
 *  the replications of the experiment. The replications of the experiment will be numbered sequentially
 *  starting at this supplied integer and increasing by 1 for each replication executed.  For example,
 *  if the starting replication identifier [startingRepId] is 5 and there are 6 replications executed in the experiment,
 *  the 6 replications will be numbered with identifiers: 5, 6, 7, 8, 9, 10.  The default value is 1.
 *
 * @param name The name of the experiment
 */

open class Experiment @JvmOverloads constructor(
    startingRepId: Int = 1,
    name: String = "Experiment_${++myCounter_}"
) : ExperimentIfc {
    init {
        require(startingRepId >= 1) { "The starting replication id number must be >= 1" }
    }

    /**
     * Creates an experiment based on the supplied run parameters
     * @param runParameters the parameters to use
     */
    @Suppress("unused")
    constructor(runParameters: ExperimentRunParametersIfc) : this() {
        changeRunParameters(runParameters)
    }

    override val experimentId: Int = ++myCounter_

    override var experimentName: String = name

    private var myDesiredReplications: Int = 1

    override var runErrorMsg: String = ""

    /**
     * The number of replications to run for this experiment
     *
     */
    override var numberOfReplications: Int
        get() = myDesiredReplications
        set(value) {
            numberOfReplications(value, false)
        }

    override var startingRepId: Int = startingRepId
        set(value) {
            require(value >= 1) { "The starting replication id number must be >= 1" }
            field = value
        }

    override var numChunks: Int = 1

    override val repIdRange: IntRange
        get() = IntRange(startingRepId, startingRepId + numberOfReplications - 1)

    override var runName: String = ""
        get() = field.ifEmpty { repIdRange.toString() }

    /**
     * The current number of replications that have been run for this experiment
     */
    override var currentReplicationNumber : Int = 0
        protected set

    /**
     * The specified length of each planned replication for this experiment. The
     * default is Double.POSITIVE_INFINITY.
     */
    override var lengthOfReplication : Double = Double.POSITIVE_INFINITY
        set(value) {
            require(value > 0.0) { "Simulation replication length must be > 0.0" }
            field = value
        }

    /**
     * The length of time from the start of an individual replication to the
     * warm-up event for that replication.
     */
    override var lengthOfReplicationWarmUp : Double = 0.0 // zero is no warmup
        set(value) {
            require(value >= 0.0) { "Warmup time cannot be less than zero" }
            field = value
        }

    /**
     * A flag to indicate whether each replication within the experiment
     * should be re-initialized at the beginning of each replication. True means
     * that it will be re-initialized.
     */
    override var replicationInitializationOption: Boolean = true

    /**
     * The maximum allowable execution time "wall" clock time for an individual
     * replication to complete processing in nanoseconds.
     * Set the maximum allotted (suggested) execution (real) clock for
     * a replication. This is a proposed value because the execution time
     * requirement is only checked after the completion of each replication
     * After it is discovered that cumulative time for executing the replication has
     * exceeded the maximum time, then the process will be ended
     * (perhaps) not completing other replications.
     */
    override var maximumAllowedExecutionTimePerReplication: Duration = Duration.ZERO
        // zero means not used
        set(value) {
            require(value > Duration.ZERO) { "The maximum number of execution time (clock time) must be > 0.0" }
            field = value
        }

    /**
     * The reset start stream option This option indicates whether the
     * random variables used during the experiment will be reset to their
     * starting stream prior to running the first replication. The default is
     * FALSE. This ensures that the random variable's streams WILL NOT be reset
     * prior to running the experiment. This will cause different experiments or
     * the same experiment run multiple times that use the same random variables
     * (via the same model) to continue within their current stream. Therefore,
     * the experiments will be independent when invoked within the same program
     * execution. To get common random number (CRN), run the experiments in
     * different program executions OR set this option to true prior to running
     * the experiment again within the same program invocation.
     */
    override var resetStartStreamOption: Boolean = false

    /**
     * The reset next sub stream option This option indicates whether the
     * random variables used during the replication within the experiment will
     * be reset to their next sub-stream after running each replication. The
     * default is TRUE. This ensures that the random variables will jump to the
     * next sub-stream within their current stream at the end of a replication.
     * This will cause the random variables in each subsequent replication to
     * start in the same sub-stream in the underlying random number streams if
     * the replication is repeatedly used and the ResetStartStreamOption is set
     * to false (which is the default) and then jump to the next sub-stream (if
     * this option is on). This option has no effect if there is only 1
     * replication in an experiment.
     *
     * Having ResetNextSubStreamOption true assists in synchronizing the random
     * number draws from one replication to another aiding in the implementation
     * of common random numbers. Each replication within the same experiment is
     * still independent.
     */
    override var advanceNextSubStreamOption: Boolean = true

    /**
     * Indicates whether antithetic replications should be run. The
     * default is false. If set the user must supply an even number of
     * replications; otherwise an exception will be thrown. The replications
     * will no longer be independent; however, pairs of replications will be
     * independent. Thus, the number of independent samples will be one-half of
     * the specified number of replications
     */
    override var antitheticOption: Boolean = false
        protected set

    /**
     * Indicates the number of times the streams should be advanced prior to
     * running the experiment
     *
     */
    override var numberOfStreamAdvancesPriorToRunning: Int = 0
        set(value) {
            require(value > 0) { "The number times to advance the stream must be > 0" }
            field = value
        }

    /**
     * Causes garbage collection System.gc() to be invoked after each
     * replication. The default is false
     *
     */
    override var garbageCollectAfterReplicationFlag: Boolean = false

    /**
     * Holds values for each controllable parameter of the simulation
     * model.
     */
    override var experimentalControls: Map<String, Double> = mapOf()

    /**
     *
     * @return true if a control map has been supplied
     */
    override fun hasExperimentalControls(): Boolean {
        return experimentalControls.isNotEmpty()
    }

    /**
     * Sets the desired number of replications for the experiment
     *
     * @param numReps must be &gt; 0, and even (divisible by 2) if antithetic
     * option is true
     * @param antitheticOption controls whether antithetic replications occur
     */
    override fun numberOfReplications(numReps: Int, antitheticOption: Boolean) {
        require(numReps > 0) { "Number of replications <= 0" }
        if (antitheticOption) {
            require(numReps % 2 == 0) { "Number of replications must be even if antithetic option is on." }
            this.antitheticOption = true
        }
        myDesiredReplications = numReps
    }

    /**
     * Checks if the current number of replications that have been executed is
     * less than the number of replications specified.
     *
     * @return true if more
     */
    override fun hasMoreReplications(): Boolean {
        return currentReplicationNumber < numberOfReplications
    }

    /**
     *  Changes the experiment run parameters for the experiment.
     *  This does not include the current number of replications or the experiment's id.
     *  Any property in ExperimentRunParametersIfc may be changed.
     *
     */
    final override fun changeRunParameters(runParameters: ExperimentRunParametersIfc) {
        experimentName = runParameters.experimentName
        startingRepId = runParameters.startingRepId
        numberOfReplications = runParameters.numberOfReplications
        lengthOfReplication = runParameters.lengthOfReplication
        lengthOfReplicationWarmUp = runParameters.lengthOfReplicationWarmUp
        replicationInitializationOption = runParameters.replicationInitializationOption
        resetStartStreamOption = runParameters.resetStartStreamOption
        advanceNextSubStreamOption = runParameters.advanceNextSubStreamOption
        antitheticOption = runParameters.antitheticOption
        numChunks = runParameters.numChunks
        if (runParameters.numberOfStreamAdvancesPriorToRunning > 0) {
            numberOfStreamAdvancesPriorToRunning = runParameters.numberOfStreamAdvancesPriorToRunning
        }
        if (runParameters.maximumAllowedExecutionTimePerReplication > Duration.ZERO) {
            maximumAllowedExecutionTimePerReplication = runParameters.maximumAllowedExecutionTimePerReplication
        }
        garbageCollectAfterReplicationFlag = runParameters.garbageCollectAfterReplicationFlag
    }

    /**
     * Returns a new Experiment based on this experiment.
     *
     * Essentially a clone, except for the id and the current replication number
     * being zero
     *
     * @return a new Experiment
     */
    override fun experimentInstance(): Experiment {
        val n = Experiment()
        n.experimentName = experimentName
        n.startingRepId = startingRepId
        n.numberOfReplications = numberOfReplications
        n.lengthOfReplication = lengthOfReplication
        n.lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
        n.replicationInitializationOption = replicationInitializationOption
        n.resetStartStreamOption = resetStartStreamOption
        n.advanceNextSubStreamOption = advanceNextSubStreamOption
        n.antitheticOption = antitheticOption
        n.numChunks = numChunks
        if (numberOfStreamAdvancesPriorToRunning > 0) {
            n.numberOfStreamAdvancesPriorToRunning = numberOfStreamAdvancesPriorToRunning
        }
        if (maximumAllowedExecutionTimePerReplication > Duration.ZERO) {
            n.maximumAllowedExecutionTimePerReplication = maximumAllowedExecutionTimePerReplication
        }
        n.garbageCollectAfterReplicationFlag = garbageCollectAfterReplicationFlag
        return n
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Experiment Name: $experimentName")
        sb.appendLine("Experiment ID: $experimentId")
        sb.appendLine("Planned number of replications: $numberOfReplications")
        sb.appendLine("Number of chunks: $numChunks")
        sb.appendLine("Replication initialization option: $replicationInitializationOption")
        sb.appendLine("Antithetic option: $antitheticOption")
        sb.appendLine("Reset start stream option: $resetStartStreamOption")
        sb.appendLine("Reset next sub-stream option: $advanceNextSubStreamOption")
        sb.appendLine("Number of stream advancements: $numberOfStreamAdvancesPriorToRunning")
        sb.appendLine("Planned time horizon for replication: $lengthOfReplication")
        sb.appendLine("Warm up time period for replication: $lengthOfReplicationWarmUp")
        val et = maximumAllowedExecutionTimePerReplication
        if (et == Duration.ZERO) {
            sb.appendLine("Maximum allowed replication execution time not specified.")
        } else {
            sb.append("Maximum allowed replication execution time: ")
            sb.append(et)
            sb.appendLine(" nanoseconds.")
        }
        sb.appendLine("Current Replication Number: $currentReplicationNumber")
        return sb.toString()
    }

    /**
     * Resets the current replication number to zero
     *
     */
    internal fun resetCurrentReplicationNumber() {
        currentReplicationNumber = 0
    }

    /**
     * Increments the number of replications that has been executed
     * Called internally by Model during the replication process
     */
    internal fun incrementCurrentReplicationNumber() {
        currentReplicationNumber = currentReplicationNumber + 1
    }

}