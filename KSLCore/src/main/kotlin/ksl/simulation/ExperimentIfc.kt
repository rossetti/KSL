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

import kotlin.time.Duration

interface ExperimentIfc {

    /**
     *  The name of the experiment
     */
    var experimentName: String

    /**
     *  The identity of the experiment
     */
    val experimentId : Int

    /**
     * The number of replications to run for this experiment
     *
     */
    var numberOfReplications: Int

    /**
     * Sets the desired number of replications for the experiment
     *
     * @param numReps must be &gt; 0, and even (divisible by 2) if antithetic
     * option is true
     * @param antitheticOption controls whether antithetic replications occur
     */
    fun numberOfReplications(numReps: Int, antitheticOption: Boolean)

    /**
     * The current number of replications that have been run for this experiment
     */
    val currentReplicationNumber: Int

    /**
     * The specified length of each planned replication for this experiment. The
     * default is Double.POSITIVE_INFINITY.
     */
    var lengthOfReplication: Double

    /**
     * The length of time from the start of an individual replication to the
     * warm-up event for that replication.
     */
    var lengthOfReplicationWarmUp: Double

    /**
     * A flag to indicate whether each replication within the experiment
     * should be re-initialized at the beginning of each replication. True means
     * that it will be re-initialized.
     */
    var replicationInitializationOption: Boolean

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
    var maximumAllowedExecutionTimePerReplication: Duration

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
    var resetStartStreamOption: Boolean

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
    var advanceNextSubStreamOption: Boolean

    /**
     * Indicates whether antithetic replications should be run. The
     * default is false. If set the user must supply an even number of
     * replications; otherwise an exception will be thrown. The replications
     * will no longer be independent; however, pairs of replications will be
     * independent. Thus, the number of independent samples will be one-half of
     * the specified number of replications
     */
    val antitheticOption: Boolean

    /**
     * Indicates the number of times the streams should be advanced prior to
     * running the experiment
     *
     */
    var numberOfStreamAdvancesPriorToRunning: Int

    /**
     * Causes garbage collection System.gc() to be invoked after each
     * replication. The default is false
     *
     */
    var garbageCollectAfterReplicationFlag: Boolean

    /**
     * Holds values for each controllable parameter of the simulation
     * model.
     */
    var experimentalControls: Map<String, Double>?

    /**
     *
     * @return true if a control map has been supplied
     */
    fun hasExperimentalControls(): Boolean

    /**
     * Checks if the current number of replications that have been executed is
     * less than the number of replications specified.
     *
     * @return true if more
     */
    fun hasMoreReplications(): Boolean

    /**
     * Sets all attributes of this experiment to the same values as the supplied
     * experiment (except for id)
     *
     * @param e the experiment to copy
     */
    fun setExperiment(e: Experiment)

}