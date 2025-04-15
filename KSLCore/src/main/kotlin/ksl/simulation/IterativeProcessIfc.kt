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

import kotlinx.datetime.Instant
import kotlin.time.Duration

interface IterativeProcessStatusIfc {
    /**
     *  Indicates the status of the iterative process
     */
    val endingStatus: IterativeProcessIfc.EndingStatus

    /**
     * A flag to indicate whether the iterative process is done A iterative
     * process can be done if: 1) it ran all of its steps 2) it was ended by a
     * client prior to completing all of its steps 3) it ended because it
     * exceeded its maximum allowable execution time before completing all of
     * its steps. 4) its end condition was satisfied
     *
     * @return true if done
     */
    val isDone: Boolean

    /**
     * Returns if the elapsed execution time exceeds the maximum time allowed.
     * Only true if the maximum was set and elapsed time is greater than or
     * equal to getMaximumAllowedExecutionTime()
     *
     * @return true if the execution time exceeds
     * getMaximumAllowedExecutionTime()
     */
    val isExecutionTimeExceeded: Boolean

    /**
     * Returns system time in nanoseconds that the iterative process started
     *
     * @return the number as a long
     */
    val beginExecutionTime: Instant

    /**
     * Gets the clock time as a Duration since the iterative process was
     * initialized
     *
     * @return a Duration representing the elapsed time
     */
    val elapsedExecutionTime: Duration

    /**
     * Returns system time in nanoseconds that the iterative process ended
     *
     * @return the number as a long
     */
    val endExecutionTime: Instant

    /**
     * The maximum allotted (suggested) execution (real) clock for the
     * entire iterative process in nanoseconds. This is a suggested time because the execution
     * time requirement is only checked after the completion of an individual
     * step After it is discovered that cumulative time for executing the step
     * has exceeded the maximum time, then the iterative process will be ended
     * (perhaps) not completing other steps.
     */
    var maximumAllowedExecutionTime: Duration

    /**
     * Returns the number of steps completed since the iterative process was
     * last initialized
     *
     * @return the number of steps completed
     */
    val numberStepsCompleted: Int

    /**
     * Checks if the iterative process is in the created state. If the
     * iterative process is in the created state this method will return true
     *
     * @return true if in the created state
     */
    val isCreated: Boolean

    /**
     * Checks if the iterative process is in the initialized state After the
     * iterative process has been initialized this method will return true
     *
     * @return true if initialized
     */
    val isInitialized: Boolean

    /**
     * An iterative process is running if it has been told to run (i.e.
     * runNext()) but has not yet been told to end().
     *
     */
    val isRunning: Boolean

    /**
     * Checks if the iterative process is in the completed step state After the
     * iterative process has successfully completed a step this property will be true
     */
    val isStepCompleted: Boolean

    /**
     * Checks if the iterative process is in the ended state. After the iterative
     * process has been ended this property will return true
     */
    val isEnded: Boolean

    /**
     * The iterative process may end by a variety of means, this  checks
     * if the iterative process ended because it ran all of its steps, true if all completed
     */
    val allStepsCompleted: Boolean

    /**
     * The iterative process may end by a variety of means, this method checks
     * if the iterative process ended because it was stopped, true if it was stopped via stop()
     */
    val stoppedByCondition: Boolean

    /**
     * The iterative process may end by a variety of means, this method checks
     * if the iterative process ended but was unfinished, not all steps
     * completed
     *
     *
     * @return true if the process is not finished
     */
    val isUnfinished: Boolean

    /**
     * A string message for why stop() was called.
     *
     * @return the message
     */
    val stoppingMessage: String?

    /**
     * Returns the stopping flag
     *
     * @return true if the process has been told to stop via stop()
     */
    val stopping: Boolean

    /**
     * Indicates that the iterative process is currently running an individual
     * step
     *
     * @return true if the step is in progress
     */
    val isRunningStep: Boolean

    /**
     * Indicates that the iterative process ended because of no steps
     *
     */
    val noStepsExecuted: Boolean
}

/**
 *
 * @author rossetti
 */
interface IterativeProcessIfc : IterativeProcessStatusIfc {

    enum class EndingStatus(val msg: String) {
        NO_STEPS_EXECUTED("No steps to run."),
        COMPLETED_ALL_STEPS("Completed all steps."),
        EXCEEDED_EXECUTION_TIME("Exceeded its maximum execution time."),
        MET_STOPPING_CONDITION("Stopped based on a condition."),
        UNFINISHED("The process is not finished")
    }

    /**
     * Returns if the elapsed execution time exceeds the maximum time allowed.
     * Only true if the maximum was set and elapsed time is greater than or
     * equal to getMaximumAllowedExecutionTime()
     *
     * @return true if the execution time exceeds
     * getMaximumAllowedExecutionTime()
     */
    override val isExecutionTimeExceeded: Boolean
        get() = endingStatus == EndingStatus.EXCEEDED_EXECUTION_TIME

    /**
     * Gets the clock time as a Duration since the iterative process was
     * initialized
     *
     * @return a Duration representing the elapsed time
     */
    override val elapsedExecutionTime: Duration
        get() = endExecutionTime - beginExecutionTime

    /**
     * The iterative process may end by a variety of means, this  checks
     * if the iterative process ended because it ran all of its steps, true if all completed
     */
    override val allStepsCompleted: Boolean
        get() = endingStatus == EndingStatus.COMPLETED_ALL_STEPS

    /**
     * The iterative process may end by a variety of means, this method checks
     * if the iterative process ended because it was stopped, true if it was stopped via stop()
     */
    override val stoppedByCondition: Boolean
        get() = endingStatus == EndingStatus.MET_STOPPING_CONDITION

    /**
     * The iterative process may end by a variety of means, this method checks
     * if the iterative process ended but was unfinished, not all steps
     * completed
     *
     *
     * @return true if the process is not finished
     */
    override val isUnfinished: Boolean
        get() = endingStatus == EndingStatus.UNFINISHED

    /**
     * Initializes the iterative process prior to running any steps This must be
     * done prior to calling runNext();
     */
    fun initialize()

    /**
     * Runs the next step in the iterative process
     */
    fun runNext()

    /**
     * Runs all the steps of the iterative process.
     *
     * If the iterative process has not been initialized, then it will
     * automatically be initialized.
     *
     * After attempting to run the steps, the process will be in the end()
     * state. The process may or may not complete all of its steps.
     *
     */
    fun run()

    /**
     * The iterative process will continue until there are no more steps or its
     * maximum execution time has been reached, whichever comes first. If this
     * method is called the iterative process will stop processing (terminate)
     * before the next step and not process the next step in the process. The
     * current step will be completed. This method can be used to stop the
     * process at an arbitrary step. Once stopped, the process must be
     * restarted.
     *
     * @param msg an option message to indicate the reason for stopping
     */
    fun end(msg: String? = null)

    /**
     * This sets a flag to indicate to the process that it should stop after the
     * next step is completed. This is different from end(). Calling end()
     * immediately places the process in the End state. The process needs to be
     * in a valid state before end() can be used. Calling stop tells the process
     * to eventually get into the end state. stop() can be used to arbitrarily
     * stop the process based on some user defined condition.
     *
     * @param msg A string to represent the reason for the stopping
     */
    fun stop(msg: String? = null)

    /**
     * Indicates that the iterative process ended because of no steps
     *
     */
    override val noStepsExecuted: Boolean
        get() = endingStatus == EndingStatus.NO_STEPS_EXECUTED

}