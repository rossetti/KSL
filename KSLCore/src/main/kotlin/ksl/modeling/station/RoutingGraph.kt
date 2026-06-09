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

/**
 *  Implemented by network nodes that can describe their static onward routing,
 *  enabling a [StationNetwork] to assemble an introspectable graph and to
 *  validate the topology before a run.
 *
 *  "Static" routing excludes per-instance dynamic routing carried on a QObject
 *  (a [Route] attached as the instance's sender); routes are accounted for
 *  separately by the network via [StationNetwork.registerRoute].
 */
interface RoutingOutletsIfc {

    /**
     *  The statically known receivers this node may route to. Best-effort: empty
     *  when the node has no static link or when its routing is opaque (for example,
     *  a probabilistic sender whose destinations are not introspectable).
     */
    fun outlets(): List<QObjectReceiverIfc>

    /**
     *  True if this node has any onward routing configured (a static next receiver,
     *  a station-level sender, or a class route). A non-terminal node for which this
     *  is false — and which is not a non-terminal step of a registered route — is a
     *  dangling node and fails validation.
     */
    val hasOnwardRouting: Boolean
}

/** A directed connection between two registered nodes of a [StationNetwork], by name. */
data class NetworkArc(val from: String, val to: String)
