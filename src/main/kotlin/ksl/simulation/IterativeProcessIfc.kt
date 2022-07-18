/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.simulation

import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.toDuration

/**
 *
 * @author rossetti
 */
interface IterativeProcessIfc  {
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

    //TODO use Duration and new time API, eventually

    /**
     * Returns system time in nanoseconds that the iterative process started
     *
     * @return the number as a long
     */
    val beginExecutionTime: Long

    /**
     * Gets the clock time in nanoseconds since the iterative process was
     * initialized
     *
     * @return a long representing the elapsed time
     */
    val elapsedExecutionTime: Long
        get() {
            return if (beginExecutionTime > 0) {
                (System.nanoTime() - beginExecutionTime)
            } else {
                0
            }
        }

    /**
     * Returns system time in nanoseconds that the iterative process ended
     *
     * @return the number as a long
     */
    val endExecutionTime: Long

    /**
     * The maximum allotted (suggested) execution (real) clock for the
     * entire iterative process in nanoseconds. This is a suggested time because the execution
     * time requirement is only checked after the completion of an individual
     * step After it is discovered that cumulative time for executing the step
     * has exceeded the maximum time, then the iterative process will be ended
     * (perhaps) not completing other steps.
     */
    var maximumAllowedExecutionTime: Long

    /**
     * Returns the number of steps completed since the iterative process was
     * last initialized
     *
     * @return the number of steps completed
     */
    val numberStepsCompleted: Long

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
     */
    fun end()

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
     * This sets a flag to indicate to the process that is should stop after the
     * next step is completed. This is different than end(). Calling end()
     * immediately places the process in the End state. The process needs to be
     * in a valid state before end() can be used. Calling stop tells the process
     * to eventually get into the end state. stop() can be used to arbitrarily
     * stop the process based on some user defined condition.
     */
    fun stop()

    /**
     * This sets a flag to indicate to the process that is should stop after the
     * next step is completed. This is different than end(). Calling end()
     * immediately places the process in the End state. The process needs to be
     * in a valid state before end() can be used. Calling stop tells the process
     * to eventually get into the end state. stop() can be used to arbitrarily
     * stop the process based on some user defined condition.
     *
     * @param msg A string to represent the reason for the stopping
     */
    fun stop(msg: String? = null)

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