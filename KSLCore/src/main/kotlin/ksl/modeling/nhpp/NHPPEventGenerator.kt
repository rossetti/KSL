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
package ksl.modeling.nhpp

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.GeneratorActionIfc
import ksl.modeling.elements.EventGeneratorIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.modeling.variable.RandomSourceCIfc
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 * @param parent the parent
 * @param rateFunction the rate function
 * @param generatorAction   the listener for generation
 * @param lastRate  the last rate
 * @param name the name to assign
 */
open class NHPPEventGenerator(
    parent: ModelElement,
    rateFunction: InvertibleCumulativeRateFunctionIfc,
    generatorAction: GeneratorActionIfc,
    lastRate: Double = Double.NaN,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    theName: String? = null
) : ModelElement(parent, theName), EventGeneratorIfc, RNStreamControlIfc by stream {

    constructor(
        parent: ModelElement,
        rateFunction: InvertibleCumulativeRateFunctionIfc,
        generatorAction: GeneratorActionIfc,
        lastRate: Double = Double.NaN,
        streamNum: Int,
        name: String? = null
    ) : this(parent, rateFunction, generatorAction, lastRate, KSLRandom.rnStream(streamNum), name)

    private val myTBARV: NHPPTimeBtwEventRV = NHPPTimeBtwEventRV(this, rateFunction, lastRate, stream)

    private val myEventGenerator: EventGenerator = EventGenerator(this, generatorAction, myTBARV, myTBARV)

    override fun turnOnGenerator(t: Double) {
        myEventGenerator.turnOnGenerator(t)
    }

    override fun turnOnGenerator(r: RandomIfc) {
        myEventGenerator.turnOnGenerator(r)
    }

    override fun turnOffGenerator() {
        myEventGenerator.turnOffGenerator()
    }

    override val isStarted: Boolean
        get() = myEventGenerator.isStarted

    override var startOnInitializeOption: Boolean
        get() = myEventGenerator.startOnInitializeOption
        set(value) {
            myEventGenerator.startOnInitializeOption = value
        }

    override fun suspend() {
        myEventGenerator.suspend()
    }

    override val isSuspended: Boolean
        get() = myEventGenerator.isSuspended

    override fun resume() {
        myEventGenerator.resume()
    }

    override val isDone: Boolean
        get() = myEventGenerator.isDone

    override val maximumNumberOfEvents: Long
        get() = myEventGenerator.maximumNumberOfEvents

    override val timeBetweenEvents: RandomIfc
        get() = myEventGenerator.timeBetweenEvents

    override fun setTimeBetweenEvents(timeBtwEvents: RandomIfc, maxNumEvents: Long) {
        require(timeBtwEvents is NHPPTimeBtwEventRV) {"The time between events random variable for the generator must be a NHPPTimeBtwEventRV" }
        myEventGenerator.setTimeBetweenEvents(timeBtwEvents, maxNumEvents)
    }

    override fun setInitialTimeBetweenEventsAndMaxNumEvents(
        initialTimeBtwEvents: RandomIfc,
        initialMaxNumEvents: Long
    ) {
        require(initialTimeBtwEvents is NHPPTimeBtwEventRV) {"The time between events random variable for the generator must be a NHPPTimeBtwEventRV" }
        myEventGenerator.setInitialTimeBetweenEventsAndMaxNumEvents(initialTimeBtwEvents, initialMaxNumEvents)
    }

    override val initialTimeUntilFirstEvent: RandomSourceCIfc
        get() = myEventGenerator.initialTimeUntilFirstEvent

    override val endingTime: Double
        get() = myEventGenerator.endingTime

    override var initialEndingTime: Double
        get() = myEventGenerator.initialEndingTime
        set(value) {
            myEventGenerator.initialEndingTime = value
        }

    override val eventCount: Long
        get() = myEventGenerator.eventCount

    override val initialMaximumNumberOfEvents: Long
        get() = myEventGenerator.initialMaximumNumberOfEvents

    override val initialTimeBtwEvents: RandomIfc
        get() = myEventGenerator.initialTimeBtwEvents

    override val isEventPending: Boolean
        get() = myEventGenerator.isEventPending
}