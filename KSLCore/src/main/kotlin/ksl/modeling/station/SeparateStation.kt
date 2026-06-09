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
import ksl.simulation.ModelElement

/** Read-only view of a [SeparateStation]. */
interface SeparateStationCIfc {
    /** The number of member instances released by the station. */
    val numSeparated: CounterCIfc
}

/**
 *  Separates a batch formed by a [BatchStation] back into its member instances,
 *  sending each member onward. A batch is recognized by an attached object that is
 *  a list of QObject members; the batch wrapper is discarded and the original
 *  members continue. A non-batch instance is passed through unchanged.
 *
 *  @param parent the model element serving as this station's parent
 *  @param nextReceiver where released members are sent
 *  @param name the name of the station
 */
class SeparateStation internal constructor(
    private val network: StationNetwork,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : ModelElement(network, name), QObjectReceiverIfc, RoutingOutletsIfc, SeparateStationCIfc {

    private var myNextReceiver: QObjectReceiverIfc = nextReceiver

    /** Sets the receiver of released members. */
    fun nextReceiver(receiver: QObjectReceiverIfc) {
        myNextReceiver = receiver
    }

    private val myNumSeparated = Counter(this, "${this.name}:NumSeparated")
    override val numSeparated: CounterCIfc
        get() = myNumSeparated

    override fun outlets(): List<QObjectReceiverIfc> =
        if (myNextReceiver === NotImplementedReceiver) emptyList() else listOf(myNextReceiver)

    override val hasOnwardRouting: Boolean
        get() = myNextReceiver !== NotImplementedReceiver

    override fun receive(arrivingQObject: QObject) {
        val members = (arrivingQObject.attachedObject as? List<*>)?.filterIsInstance<QObject>()
        if (members != null && members.isNotEmpty()) {
            // batch wrapper is discarded here; verify it didn't hold any allocations
            // (members keep their own allocations and continue with them)
            network.verifyNoAllocations(arrivingQObject, this.name)
            for (member in members) {
                myNumSeparated.increment()
                myNextReceiver.receive(member)
            }
        } else {
            // not a batch; pass through unchanged
            myNumSeparated.increment()
            myNextReceiver.receive(arrivingQObject)
        }
    }
}
