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

/** Read-only view of a [JoinStation]. */
interface JoinStationCIfc {
    /** Time-weighted number of parents currently waiting for their children. */
    val numWaitingParents: TWResponseCIfc

    /** The number of parents released (joined). */
    val numJoined: CounterCIfc
}

/**
 *  The receive-side of a fork-join pair: holds each parent until all of its
 *  children have arrived, then emits the parent onward. A parent and its
 *  expected child count are registered by the paired [ForkStation] at fork
 *  time; the join holds state keyed by `parent.id` and releases the parent
 *  when `arrivedChildren == expectedChildren` regardless of arrival order
 *  (parent-first or children-first).
 *
 *  The join has two named input endpoints:
 *  - [parentInput] (index 0): receives the parent (after any concurrent
 *    parent-side processing).
 *  - [childInput] (index 1): receives each child as it finishes the
 *    child-side path; children are *absorbed* here (not forwarded).
 *
 *  The join is parented to the network and registered by name; route a
 *  downstream node to a specific input with the `"joinName#0"` (parent) or
 *  `"joinName#1"` (child) target syntax (consistent with NWay/Match).
 */
class JoinStation internal constructor(
    private val network: StationNetwork,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : ModelElement(network, name), QObjectReceiverIfc, RoutingOutletsIfc, JoinStationCIfc {

    /**
     *  Default receive: delegates to [parentInput] so the join can be referenced as a
     *  single registered node when routing a parent to it (the child path is reached
     *  via the `#1` input or [childInput] directly).
     */
    override fun receive(arrivingQObject: QObject) {
        myParentInput.receive(arrivingQObject)
    }

    private var myNextReceiver: QObjectReceiverIfc = nextReceiver

    /** Sets the receiver of joined parents. */
    fun nextReceiver(receiver: QObjectReceiverIfc) {
        myNextReceiver = receiver
    }

    override fun outlets(): List<QObjectReceiverIfc> =
        if (myNextReceiver === NotImplementedReceiver) emptyList() else listOf(myNextReceiver)

    override val hasOnwardRouting: Boolean
        get() = myNextReceiver !== NotImplementedReceiver

    private class State {
        var parent: ModelElement.QObject? = null
        var arrived: Int = 0
        var expected: Int = -1
    }

    private val states = mutableMapOf<Long, State>()
    private val childToParentId = mutableMapOf<ModelElement.QObject, Long>()

    private val myNumWaitingParents = TWResponse(this, "${this.name}:NumWaitingParents")
    override val numWaitingParents: TWResponseCIfc
        get() = myNumWaitingParents

    private val myNumJoined = Counter(this, "${this.name}:NumJoined")
    override val numJoined: CounterCIfc
        get() = myNumJoined

    /**
     *  Called by the paired [ForkStation] at fork time to declare how many
     *  children to expect for [parent]. Order is independent of arrivals.
     */
    internal fun registerParent(parent: ModelElement.QObject, expectedChildren: Int) {
        require(expectedChildren >= 1) { "expected children must be >= 1" }
        val s = states.getOrPut(parent.id) { State() }
        s.expected = expectedChildren
    }

    /**
     *  Called by the paired [ForkStation] to associate a freshly created
     *  [child] with its [parent]; the child carries no QObject-side marking,
     *  so the join consults this side map when the child arrives.
     */
    internal fun linkChild(child: ModelElement.QObject, parent: ModelElement.QObject) {
        childToParentId[child] = parent.id
    }

    /** Returns the receiver endpoint where the parent will eventually arrive (index 0). */
    fun parentInput(): QObjectReceiverIfc = myParentInput

    /** Returns the receiver endpoint where children will arrive (index 1). */
    fun childInput(): QObjectReceiverIfc = myChildInput

    /** Returns the input endpoint by [index] — 0 for parent, 1 for child. */
    fun input(index: Int): QObjectReceiverIfc = when (index) {
        0 -> myParentInput
        1 -> myChildInput
        else -> error("join input index $index out of range (0 or 1)")
    }

    private val myParentInput: QObjectReceiverIfc = QObjectReceiverIfc { p ->
        val s = states.getOrPut(p.id) { State() }
        s.parent = p
        myNumWaitingParents.value = countWaiting().toDouble()
        tryEmit(p.id)
    }

    private val myChildInput: QObjectReceiverIfc = QObjectReceiverIfc { c ->
        // child is absorbed here; it must have released any held resources first
        network.verifyNoAllocations(c, "${this.name}#child")
        val pid = childToParentId.remove(c)
            ?: error("JoinStation '${this.name}' received unlinked child ${c.name}; child arrived without a paired fork registration")
        val s = states[pid] ?: error("JoinStation '${this.name}' missing state for parent id $pid")
        s.arrived++
        tryEmit(pid)
    }

    private fun tryEmit(pid: Long) {
        val s = states[pid] ?: return
        val parent = s.parent
        if (parent != null && s.expected > 0 && s.arrived == s.expected) {
            states.remove(pid)
            myNumWaitingParents.value = countWaiting().toDouble()
            myNumJoined.increment()
            myNextReceiver.receive(parent)
        }
    }

    private fun countWaiting(): Int = states.count { it.value.parent != null }

    override fun initialize() {
        states.clear()
        childToParentId.clear()
        myNumWaitingParents.value = 0.0
    }
}
