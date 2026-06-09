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

/** Read-only view of a [ForkStation]. */
interface ForkStationCIfc {
    /** The number of parents that have entered (forked). */
    val numForked: CounterCIfc

    /** The total number of children spawned. */
    val numChildrenCreated: CounterCIfc
}

/**
 *  The send-side of a fork-join pair: receives a parent QObject, spawns a
 *  variable number of children, and routes the parent onward concurrently
 *  with the children. Children are created by [childFactory] and registered
 *  with the paired [join] so it can reunite them with the parent.
 *
 *  Spawning is synchronous (event-graph): the children are emitted to
 *  [childReceiver] and the parent is emitted to its onward receiver within
 *  the same `receive` call. The two paths can include any number of stations
 *  before the join's parent and child inputs.
 *
 *  Children are *internal* to the network — they are not counted in the
 *  owning network's number-in-system (they are not received at an ingress)
 *  and they are absorbed at the join (they never reach a sink). The parent
 *  carries the network's NS and completion accounting.
 *
 *  The child QObjects are created by the fork (since `QObject` is an inner
 *  class of `ModelElement`); [childFactory] only *configures* each freshly
 *  created child (set type, attach value object, etc.) — same idiom as a
 *  source station's `marking` action. Supply `null` for a default child.
 *
 *  @param parent the model element serving as this station's parent
 *  @param join the paired [JoinStation] that will reunite the children with the parent
 *  @param childCount how many children to spawn for a given parent (must return >= 1)
 *  @param childFactory optional per-child configuration applied to each freshly
 *                     created child; null means an unconfigured QObject
 *  @param childReceiver where each newly created child is first sent
 *  @param nextReceiver where the parent continues
 *  @param name the name of the station
 */
class ForkStation(
    parent: ModelElement,
    private val join: JoinStation,
    private val childCount: ChildCountIfc,
    private val childFactory: ChildFactoryIfc? = null,
    childReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : ModelElement(parent, name), QObjectReceiverIfc, RoutingOutletsIfc, ForkStationCIfc {

    private var myChildReceiver: QObjectReceiverIfc = childReceiver
    private var myNextReceiver: QObjectReceiverIfc = nextReceiver

    /** Sets the receiver that newly spawned children are sent to. */
    fun childReceiver(receiver: QObjectReceiverIfc) {
        myChildReceiver = receiver
    }

    /** Sets the receiver the parent continues to. */
    fun nextReceiver(receiver: QObjectReceiverIfc) {
        myNextReceiver = receiver
    }

    private val myNumForked = Counter(this, "${this.name}:NumForked")
    override val numForked: CounterCIfc
        get() = myNumForked

    private val myNumChildren = Counter(this, "${this.name}:NumChildrenCreated")
    override val numChildrenCreated: CounterCIfc
        get() = myNumChildren

    override fun outlets(): List<QObjectReceiverIfc> {
        val result = mutableListOf<QObjectReceiverIfc>()
        if (myChildReceiver !== NotImplementedReceiver) result.add(myChildReceiver)
        if (myNextReceiver !== NotImplementedReceiver) result.add(myNextReceiver)
        return result
    }

    override val hasOnwardRouting: Boolean
        get() = myChildReceiver !== NotImplementedReceiver && myNextReceiver !== NotImplementedReceiver

    override fun receive(arrivingQObject: QObject) {
        val n = childCount.countFor(arrivingQObject)
        require(n >= 1) { "ForkStation '${this.name}' child count must be >= 1 (got $n)" }
        // declare to the join how many children to expect for this parent
        join.registerParent(arrivingQObject, n)
        myNumForked.increment()
        // spawn and dispatch each child, linking it to the parent. The fork creates
        // the QObject (since QObject is an inner class of ModelElement) and the
        // factory only configures it -- same idiom as a source's marking hook.
        for (i in 0 until n) {
            val child = QObject()
            childFactory?.configureChild(arrivingQObject, child)
            join.linkChild(child, arrivingQObject)
            myNumChildren.increment()
            myChildReceiver.receive(child)
        }
        // continue the parent on its own path
        myNextReceiver.receive(arrivingQObject)
    }
}
