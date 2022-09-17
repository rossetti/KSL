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
package ksl.modeling.elements

import ksl.modeling.variable.RandomVariableCIfc
import ksl.utilities.random.RandomIfc

/** An interface to define how event generators operate.  The primary
 * subclass is EventGenerator.  Of particular note is the use of
 * initial parameters:
 *
 * initial time of first event
 * initial time between events
 * initial maximum number of events
 * initial ending time
 *
 * These parameters control the initial state of the generator at the start
 * of each replication.  The generator is re-initialized to these values at
 * the start of each replication.  There are also parameters for each of these
 * that can be changed during a replication.  The effect of that change
 * is only within the current replication.
 *
 * @author rossetti
 */
interface EventGeneratorIfc {
    /**
     * If the generator was not started upon initialization at the beginning of
     * a replication, then this method can be used to start the generator
     *
     * The generator will be started t time units after the call
     *
     * If this method is used when the generator is already started it does
     * nothing. If this method is used after the generator is done it does
     * nothing. If this method is used after the generator has been suspended it
     * does nothing. Use suspend() and resume() to suspend and resume a
     * generator that has already been started.
     *
     * @param t The time until the generator should be turned on
     */
    fun turnOnGenerator(t: Double = 0.0)

    /**
     * If the generator was not started upon initialization at the beginning of
     * a replication, then this method can be used to start the generator
     *
     * The generator will be started r.getValue() time units after the call
     *
     * If this method is used when the generator is already started it does
     * nothing. If this method is used after the generator is done it does
     * nothing. If this method is used after the generator has been suspended it
     * does nothing. Use suspend() and resume() to suspend and resume a
     * generator that has already been started.
     *
     * @param r The time until the generator should be turned on
     */
    fun turnOnGenerator(r: RandomIfc)

    /**
     * This method turns the generator off, the next scheduled generation event
     * will NOT occur, i.e. this method will also cancel a previously scheduled
     * generation event if one exists. No future events will be scheduled after
     * turning off the generator
     */
    fun turnOffGenerator()

    /**
     * This flag indicates whether the generator has started
     */
    val isStarted: Boolean

    /**
     * Sets the flag that indicates whether the generator will
     * automatically start at the beginning of a replication when initialized
     *
     * true indicates automatic start
     */
    var startOnInitializeOption: Boolean

    /**
     * Suspends the generation of events and cancels the next scheduled event
     * from the generator
     */
    fun suspend()

    /**
     * Indicates whether the generator has been suspended
     *
     * true if generator is suspended
     */
    val isSuspended: Boolean

    /**
     * Resume the generation of events according to the time between event distribution.
     */
    fun resume()

    /**
     * This method checks to see if the generator is done. In other words, if it
     * has been turned off.
     *
     * True means that it is done.
     */
    val isDone: Boolean

    /**
     * A long representing the maximum number of events for the
     * generator. Sets the maximum number of events for the generator. Must not be infinite
     * (Long.MAX_VALUE) if the current time between events is 0.0.  This only
     * controls the current replication.
     *
     */
    val maximumNumberOfEvents: Long

    /**
     * Controls the time between event random source. Must not always evaluate to
     * 0.0, if the current setting of the maximum number of events is infinite
     * (Long.MAX_VALUE).  This is only for the current replication.
     */
    val timeBetweenEvents: RandomIfc //TODO should be stream control

    /**
     * Sets the time between events and the maximum number of events for the
     * generator. These two parameters are dependent. The time between events
     * cannot always evaluate to 0.0 if the maximum number of events is infinite
     * (Long.MAX_VALUE). This method only changes these parameters for the
     * current replication. The changes take effect when the next event is
     * generated. If current number of events that have been generated is
     * greater than or equal to the supplied maximum number of events, the
     * generator will be turned off.
     *
     * @param timeBtwEvents the time between events
     * @param maxNumEvents the maximum number of events
     */
    fun setTimeBetweenEvents(timeBtwEvents: RandomIfc, maxNumEvents: Long = Long.MAX_VALUE)

    /**
     * Sets the time between events and the maximum number of events to be used
     * to initialize each replication. These parameters are dependent. The time
     * between events cannot evaluate to a constant value of 0.0 if the maximum
     * number of events is infinite (Long.MAX_VALUE)
     *
     * @param initialTimeBtwEvents the initial time between events
     * @param initialMaxNumEvents the initial maximum number of events
     */
    fun setInitialTimeBetweenEventsAndMaxNumEvents(
        initialTimeBtwEvents: RandomIfc,
        initialMaxNumEvents: Long = Long.MAX_VALUE
    )

    /**
     * Controls the random variable representing the time until the first event that is
     * used at the beginning of each replication to generate the time until the
     * first event. This change becomes effective at the beginning of the next
     * replication to execute
     */
    val initialTimeUntilFirstEvent: RandomVariableCIfc

    /**
     * Controls the ending time for generating events for the current replication. A
     * new ending time will be applied to the generator. If this change results
     * in an ending time that is less than the current time, the generator will
     * be turned off
     */
    val endingTime: Double

    /**
     * This value is used to set the ending time for generating actions for each
     * replication. Changing this variable during a replication cause the next
     * replication to use this value for its ending time.
     *
     */
    var initialEndingTime: Double

    /**
     * Gets the number of events that have been generated by the generator
     */
    val eventCount: Long

    /**
     * Controls the maximum number of events to be used to initialize each
     * replication. The time between events cannot evaluate to a constant value
     * of 0.0 if the maximum number of events is infinite (Long.MAX_VALUE). Uses
     * the current value for initial time between events
     */
    val initialMaximumNumberOfEvents: Long

    /**
     * Sets the time between events and the maximum number of events to be used
     * to initialize each replication. The time between events cannot evaluate
     * to a constant value of 0.0. The maximum number of events is kept at its
     * current value, which by default is Long.Max_Value
     *
     */
    val initialTimeBtwEvents: RandomIfc //TODO should be stream control

    /**
     *
     * true if an event is scheduled to occur for the generator
     */
    val isEventPending: Boolean
}