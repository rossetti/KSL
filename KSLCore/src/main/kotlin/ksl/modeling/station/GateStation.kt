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

import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement

/** Read-only view of a [GateStation]. */
interface GateStationCIfc {
    /** Whether the gate is currently open. */
    val isOpen: Boolean

    /** Time-weighted number of instances currently held at the gate. */
    val numHeld: TWResponseCIfc

    /** The number of instances released through the gate. */
    val numReleased: CounterCIfc
}

/**
 *  A gate that passes arriving QObject instances straight through while open, and
 *  holds them (in arrival order) while closed. Opening the gate releases all held
 *  instances. The gate is controlled externally via [open] and [close] — for
 *  example by a scheduled event, a controller, or an agent reacting to network
 *  state — making it the station-view hook for signal/condition control.
 *
 *  @param parent the model element serving as this station's parent
 *  @param nextReceiver where released instances are sent
 *  @param initiallyOpen the gate's state at the start of each replication
 *  @param name the name of the station
 */
class GateStation(
    parent: ModelElement,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    private val initiallyOpen: Boolean = true,
    name: String? = null
) : ModelElement(parent, name), QObjectReceiverIfc, RoutingOutletsIfc, GateStationCIfc {

    private var myNextReceiver: QObjectReceiverIfc = nextReceiver

    /** Sets the receiver of released instances. */
    fun nextReceiver(receiver: QObjectReceiverIfc) {
        myNextReceiver = receiver
    }

    private var myOpen: Boolean = initiallyOpen
    override val isOpen: Boolean
        get() = myOpen

    private val myHeld = ArrayDeque<QObject>()

    private val myNumHeld = TWResponse(this, "${this.name}:NumHeld")
    override val numHeld: TWResponseCIfc
        get() = myNumHeld

    private val myNumReleased = Counter(this, "${this.name}:NumReleased")
    override val numReleased: CounterCIfc
        get() = myNumReleased

    override fun outlets(): List<QObjectReceiverIfc> =
        if (myNextReceiver === NotImplementedReceiver) emptyList() else listOf(myNextReceiver)

    override val hasOnwardRouting: Boolean
        get() = myNextReceiver !== NotImplementedReceiver

    override fun receive(arrivingQObject: QObject) {
        if (myOpen) {
            release(arrivingQObject)
        } else {
            myHeld.addLast(arrivingQObject)
            myNumHeld.value = myHeld.size.toDouble()
        }
    }

    /** Opens the gate and releases all held instances in arrival order. */
    fun open() {
        myOpen = true
        while (myHeld.isNotEmpty()) {
            val q = myHeld.removeFirst()
            myNumHeld.value = myHeld.size.toDouble()
            release(q)
        }
    }

    /** Closes the gate so that subsequent arrivals are held. */
    fun close() {
        myOpen = false
    }

    private fun release(qObject: QObject) {
        myNumReleased.increment()
        myNextReceiver.receive(qObject)
    }

    override fun initialize() {
        myHeld.clear()
        myNumHeld.value = 0.0
        myOpen = initiallyOpen
    }
}
