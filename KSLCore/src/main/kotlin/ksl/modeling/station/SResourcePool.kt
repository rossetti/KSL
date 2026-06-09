/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.station

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.variable.*
import ksl.simulation.ModelElement

/** Read-only view of an [SResourcePool]. */
interface SResourcePoolCIfc {
    /** The pool's current capacity (total units). */
    val capacity: Int

    /** Time-weighted number of busy units across the pool. */
    val numBusyUnits: TWResponseCIfc

    /** Time-weighted utilization of the pool (busy units / capacity). */
    val utilization: TWResponseCIfc

    /** Current number of available (idle) units. */
    val numAvailableUnits: Int

    /** True if the pool has at least one available unit. */
    val hasAvailableUnits: Boolean

    /** The number of times units have been seized from the pool. */
    val numTimesSeized: Int
}

/**
 *  A pool of interchangeable resource units shared by one or more
 *  [ResourcePoolStation]s. Stations seize and release units from the common pool,
 *  so a freed unit can serve whichever station's queue is waiting. When a unit is
 *  released, the pool notifies its registered stations (in registration order) so
 *  they can serve their queues.
 *
 *  Phase-2 pools provide shared capacity only; per-pool schedules and failures are
 *  a later addition (a single station's own resource supports those via
 *  [SingleQStation]).
 *
 *  @param parent the model element serving as the pool's parent
 *  @param capacity the number of units in the pool (>= 1)
 *  @param name the name of the pool
 */
class SResourcePool(
    parent: ModelElement,
    capacity: Int = 1,
    name: String? = null
) : ModelElement(parent, name), SResourcePoolCIfc {
    init {
        require(capacity >= 1) { "The initial capacity of the pool must be >= 1" }
    }

    @set:KSLControl(controlType = ControlType.INTEGER, lowerBound = 1.0)
    var initialCapacity: Int = capacity
        set(value) {
            require(value >= 1) { "The initial capacity of the pool must be >= 1" }
            field = value
        }

    private var myCapacity = initialCapacity
    override val capacity: Int
        get() = myCapacity

    private val myNumBusy = TWResponse(this, "${this.name}:NumBusy")
    override val numBusyUnits: TWResponseCIfc
        get() = myNumBusy

    private fun utilCapture(x: Double): Double = if (myCapacity <= 0) 0.0 else x / myCapacity
    private val myUtil = TWResponseFunction(this::utilCapture, myNumBusy, "${this.name}:Util")
    override val utilization: TWResponseCIfc
        get() = myUtil

    private var myNumTimesSeized = 0
    override val numTimesSeized: Int
        get() = myNumTimesSeized

    override val numAvailableUnits: Int
        get() = (myCapacity - myNumBusy.value.toInt()).coerceAtLeast(0)

    override val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    private val myAvailableListeners = mutableListOf<() -> Unit>()

    /** Registers a station to be notified (to serve its queue) when units free up. */
    fun attachUnitsAvailableListener(listener: () -> Unit) {
        myAvailableListeners.add(listener)
    }

    private fun notifyUnitsAvailable() {
        // toList() guards against listeners detaching/reentrancy during iteration
        for (listener in myAvailableListeners.toList()) {
            listener()
        }
    }

    /** Seizes [amount] units. The caller must ensure availability first. */
    fun seize(amount: Int = 1) {
        require(amount > 0) { "The seize amount must be > 0" }
        require(amount <= numAvailableUnits) { "Attempted to seize more than available ($numAvailableUnits)" }
        myNumBusy.increment(amount.toDouble())
        myNumTimesSeized++
    }

    /** Releases [amount] units and notifies waiting stations that units are available. */
    fun release(amount: Int = 1) {
        require(amount > 0) { "The release amount must be > 0" }
        require(amount <= myNumBusy.value.toInt()) { "Attempted to release more units than were busy" }
        myNumBusy.decrement(amount.toDouble())
        notifyUnitsAvailable()
    }

    override fun initialize() {
        myCapacity = initialCapacity
        myNumTimesSeized = 0
    }
}
