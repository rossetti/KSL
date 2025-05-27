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

package ksl.modeling.variable

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Interval

/**
 *  @see ResponseCIfc
 */
interface TWResponseCIfc : ResponseCIfc {
    /**
     * Sets the initial value of the variable. Only relevant prior to each
     * replication. Changing during a replication has no effect until the next
     * replication.
     */
    var initialValue: Double
}

/**
 *  A time-weighted response represents a time-persistent type variable for which time-based statistics
 *  are automatically collected when the value of the response variable is assigned.
 *  @param parent the parent model element containing this response
 *  @param name the unique name of the response. If a name is not assigned (null), a name will be assigned.
 *  A common naming convention would be to name the response based on the parent's name to ensure uniqueness within
 *  the context of the parent. For example, "${this.name}:SomeResponseName", where "this" refers to the parent.
 *  @param initialValue this is the intial value of the response variable. It is only used internally.
 *  @param allowedDomain This is an interval that defines the set of legal values for the response. By default, this is
 *  [0, POSITIVE_INFINITY). If supplied, this provides a method to check if invalid values are
 *  assigned to the response. For example, if the response represents time, you might want to change the
 *  allowed domain to not include negative values.
 *  @param countLimit specifies a limit that when reached will cause counter actions to be invoked. By default, this is
 *  POSITIVE_INFINITY. A common count action would be to stop the simulation when a particular number of observations
 *  have been reached.  By default, there are no count actions. Thus, if a count limit is specified, the user
 *  is responsible for providing what to do via the functions that add count actions. Otherwise, no actions occur
 *  when the limit is reached.
 */
open class TWResponse(
    parent: ModelElement,
    name: String? = null,
    initialValue: Double = 0.0,
    allowedDomain: Interval = Interval(0.0, Double.POSITIVE_INFINITY),
    countLimit: Double = Double.POSITIVE_INFINITY,
) : Response(parent, name, initialValue, allowedDomain, countLimit), TimeWeightedIfc, TWResponseCIfc {
    
    init {
        require(allowedDomain.contains(initialValue)) { "The initial value $initialValue must be within the specified limits: $allowedDomain" }
    }

    /**
     * Sets the initial value of the variable. Only relevant prior to each
     * replication. Changing during a replication has no effect until the next
     * replication.
     */
    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        name = "initialValue",
        lowerBound = 0.0
    )
    override var initialValue: Double = initialValue
        set(value) {
            require(domain.contains(value)) { "The initial value, $value must be within the specified limits: $domain" }
            require(model.isNotRunning) {"The initial value must be set before the simulation is running."}
            field = value
        }

    /**
     *  The previous value, before the current value changed
     */
    override var previousValue: Double = initialValue
        protected set

    override var timeOfChange: Double = 0.0
        protected set

    override var previousTimeOfChange: Double = 0.0
        protected set

    override val weight: Double
        get() {
            val w = timeOfChange - previousTimeOfChange
            return if ((w < 0.0) || (w.isNaN())) {
                0.0
            } else {
                w
            }
        }

    override fun assignValue(newValue: Double) {
        require(domain.contains(newValue)) { "The value $newValue was not within the limits $domain for variable ${this.name}" }
        previousValue = myValue
        previousTimeOfChange = timeOfChange
        myValue = newValue
        timeOfChange = time
        myWithinReplicationStatistic.collect(previousValue, weight)
        notifyModelElementObservers(Status.UPDATE)
        if (emissionsOn){
            emitter.emit(Pair(timeOfChange, myValue))
        }
        if(myWithinReplicationStatistic.count == replicationCountLimit){
            notifyCountLimitActions()
        }
    }

    /**
     * Assigns the value of the variable to the supplied value. Ensures that
     * time of change is 0.0 and previous value and previous time of
     * change are the same as the current value and current time without
     * notifying any update observers
     *
     * @param value the initial value to assign
     */
    override fun assignInitialValue(value: Double) {
        require(domain.contains(value)) { "The initial value, $value must be within the specified limits: $domain" }
        myValue = value
        timeOfChange = 0.0
        previousValue = myValue //TODO should this be Double.NaN, same ensures zero weight
        previousTimeOfChange = timeOfChange //TODO should this be Double.NaN
    }

    /**
     * Increments the value of the variable by the amount supplied. Throws an
     * IllegalArgumentException if the value is negative.
     *
     * @param increase The amount to increase by. Must be non-negative.
     */
    fun increment(increase: Double = 1.0) {
        require(increase >= 0) { "Invalid argument. Attempted an negative increment." }
        value = value + increase
    }

    /**
     * Decrements the value of the variable by the amount supplied. Throws an
     * IllegalArgumentException if the value is negative.
     *
     * @param decrease The amount to decrease by. Must be non-negative.
     */
    fun decrement(decrease: Double = 1.0) {
        require(decrease >= 0) { "Invalid argument. Attempted an negative decrement." }
        value = value - decrease
    }

    override fun beforeExperiment() {
        super.beforeExperiment()
        assignInitialValue(initialValue)
    }

    override fun beforeReplication() {
        super.beforeReplication()
        assignInitialValue(initialValue)
    }

    override fun initialize() {
        super.initialize()
        // this is so at least two changes are recorded on the variable
        // to properly account for variables that have zero area throughout the replication
        value = value
    }

    override fun timedUpdate() {
        super.timedUpdate()
        // this is to capture the area under the curve up to and including the current time
        value = value
    }

    override fun warmUp() {
        super.warmUp()
        // this is so at least two changes are recorded on the variable
        // to properly account for variables that have zero area throughout the replication
        // make it think that it changed at the warm-up time to the same value
        timeOfChange = time
        value = value
    }

    override fun replicationEnded() {
        super.replicationEnded()
        // this allows time weighted to be collected all the way to end of the replication
        value = value
    }
}