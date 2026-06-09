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

import ksl.simulation.ModelElement

/**
 *  A predicate over a QObject instance, used by routers to make data-dependent
 *  routing decisions. Unlike a plain `() -> Boolean`, the predicate is given the
 *  instance being routed, so routing can depend on its type, priority, value, or
 *  attached state.
 */
fun interface QObjectPredicate {
    fun test(qObject: ModelElement.QObject): Boolean
}

/**
 *  A Router both receives a QObject and immediately sends it onward, choosing the
 *  destination as a function of the arriving instance. It is the QObject-aware
 *  generalization of [QObjectSender]: its [selectNextReceiver] is passed the
 *  instance being routed, so data-dependent routing (by type, by condition, by
 *  attribute) is expressible directly.
 *
 *  A router exposes its candidate [destinations] so that a [StationNetwork] can
 *  introspect and validate the routing graph.
 */
abstract class Router : QObjectSenderIfc, QObjectReceiverIfc, RoutingOutletsIfc {

    final override fun outlets(): List<QObjectReceiverIfc> = destinations()

    override val hasOnwardRouting: Boolean
        get() = destinations().isNotEmpty()

    private var beforeSendingAction: SendingActionIfc? = null
    private var afterSendingAction: SendingActionIfc? = null

    /** Action invoked just before the selected receiver receives the instance. */
    fun beforeSendingAction(action: SendingActionIfc) {
        beforeSendingAction = action
    }

    /** Action invoked just after the selected receiver receives the instance. */
    fun afterSendingAction(action: SendingActionIfc) {
        afterSendingAction = action
    }

    final override fun receive(arrivingQObject: ModelElement.QObject) {
        send(arrivingQObject)
    }

    final override fun send(qObject: ModelElement.QObject) {
        val selected = selectNextReceiver(qObject)
        beforeSendingAction?.action(selected, qObject)
        selected.receive(qObject)
        afterSendingAction?.action(selected, qObject)
    }

    /**
     *  Selects the receiver for the supplied [qObject]. Implementations must
     *  return a non-null receiver; supply a default/fallback to guarantee this.
     */
    abstract fun selectNextReceiver(qObject: ModelElement.QObject): QObjectReceiverIfc

    /** The possible destinations this router may select, for graph introspection. */
    abstract fun destinations(): List<QObjectReceiverIfc>

    /**
     *  Resets any per-replication routing state to its initial condition. Stateless
     *  routers need not override this. A [StationNetwork] calls this on each
     *  registered router at the start of every replication, so stateful routers
     *  (for example, round-robin) should be registered with the network.
     */
    open fun resetRouter() {}
}
