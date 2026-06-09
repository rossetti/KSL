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

import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc

/**
 *  An egress port that hands instances off to a [target] receiver — typically the
 *  [IngressStation] of another network — instead of disposing of them. This is the
 *  station-view realization of a network-to-network transfer "in a prescribed
 *  manner": an optional [transferDelay] models transport time, and an optional
 *  [transform] can re-mark/repackage the instance on hand-off.
 *
 *  The instance leaves this network (its number-in-system is decremented and a
 *  transferred event is published) but its creation time is preserved, so the
 *  receiving network records end-to-end system time when the instance is finally
 *  disposed.
 *
 *  @param network the network this transfer leaves from
 *  @param target the receiver in the destination (e.g., another network's ingress)
 *  @param transferDelay optional transport time before delivery
 *  @param transform optional action applied to the instance on hand-off
 *  @param name the name of the station
 */
class TransferStation internal constructor(
    private val network: StationNetwork,
    private val target: QObjectReceiverIfc,
    private val transferDelay: GetValueIfc? = null,
    private val transform: ((ModelElement.QObject) -> Unit)? = null,
    name: String? = null
) : ModelElement(network, name), NetworkEgress, RoutingOutletsIfc {

    override val portName: String
        get() = this.name

    // an egress is terminal for this network; the target lives in another network
    override fun outlets(): List<QObjectReceiverIfc> = emptyList()

    override val hasOnwardRouting: Boolean
        get() = true

    override fun receive(arrivingQObject: QObject) {
        // exit-time validation: allocations are local to this network and cannot
        // follow the entity into another network
        network.verifyNoAllocations(arrivingQObject, this.name)
        network.objectExited(arrivingQObject, this, transferred = true)
        transform?.invoke(arrivingQObject)
        if (transferDelay == null) {
            target.receive(arrivingQObject)
        } else {
            schedule(this::deliver, transferDelay.value, arrivingQObject)
        }
    }

    private fun deliver(event: KSLEvent<QObject>) {
        target.receive(event.message!!)
    }
}
