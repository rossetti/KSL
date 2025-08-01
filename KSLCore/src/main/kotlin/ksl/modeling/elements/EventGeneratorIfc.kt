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
package ksl.modeling.elements

import ksl.utilities.GetValueIfc
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rvariable.RVariableIfc

interface EventGeneratorTimeBtwEventsIfc {
    /**
     * Controls the time between event random source. Must not always evaluate to
     * 0.0, if the current setting of the maximum number of events is infinite
     * (Long.MAX_VALUE).  This is only for the current replication.
     */
    val timeBetweenEvents: GetValueIfc

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
    fun setTimeBetweenEvents(timeBtwEvents: GetValueIfc, maxNumEvents: Long = Long.MAX_VALUE)
}

interface EventGeneratorTimeBtwEventsRVIfc {
    /**
     * Controls the time between event random source. Must not always evaluate to
     * 0.0, if the current setting of the maximum number of events is infinite
     * (Long.MAX_VALUE).  This is only for the current replication.
     */
    val timeBetweenEvents: RVariableIfc

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
    fun setTimeBetweenEvents(timeBtwEvents: RVariableIfc, maxNumEvents: Long = Long.MAX_VALUE)
}

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
interface EventGeneratorIfc : IdentityIfc {
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
    fun turnOnGenerator(r: GetValueIfc){
        turnOnGenerator(r.value)
    }

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
     * Controls the ending time for generating events for the current replication. A
     * new ending time will be applied to the generator. If this change results
     * in an ending time that is less than the current time, the generator will
     * be turned off
     */
    val endingTime: Double

    /**
     * Gets the number of events that have been generated by the generator
     */
    val eventCount: Long

    /**
     *
     * true if an event is scheduled to occur for the generator
     */
    val isEventPending: Boolean

    /**
     * The action for the events for generation
     */
    var generatorAction: GeneratorActionIfc?

    /**
     *  Can be used to supply logic to invoke when the generator's
     *  ending time is finite and the generator is turned off.
     */
    var endGeneratorAction: EndGeneratorActionIfc?
}