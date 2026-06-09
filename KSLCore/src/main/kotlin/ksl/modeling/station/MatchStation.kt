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

private val UNKEYED_MATCH_KEY = Any()

/** Read-only view of a [MatchStation]. */
interface MatchStationCIfc {
    /** Time-weighted number of instances waiting to be matched across all inputs. */
    val numWaiting: TWResponseCIfc

    /** The number of matched sets formed (assemblies emitted). */
    val numMatched: CounterCIfc
}

/**
 *  Synchronizes (assembles) instances arriving on several inputs: when one
 *  instance is available on every input — optionally with the same matching key —
 *  the station removes one from each, combines them into a single instance
 *  (carrying the members as its attached object), and sends it onward.
 *
 *  With no [keyExtractor], any one instance from each input is matched (an
 *  unkeyed AND-join). With a [keyExtractor], only instances sharing a key value
 *  across all inputs are matched. Wire upstream nodes to the [input] endpoints.
 *
 *  Note: assembly reduces population — N members become one combined instance — so
 *  the owning network's number-in-system will not balance for assembly models;
 *  use the station's own statistics.
 *
 *  @param parent the model element serving as this station's parent
 *  @param numInputs the number of inputs to synchronize (>= 2)
 *  @param keyExtractor optional key for keyed matching; null matches one from each input
 *  @param nextReceiver where combined instances are sent
 *  @param name the name of the station
 */
class MatchStation(
    parent: ModelElement,
    val numInputs: Int,
    private val keyExtractor: ((QObject) -> Any)? = null,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : ModelElement(parent, name), RoutingOutletsIfc, MatchStationCIfc {
    init {
        require(numInputs >= 2) { "a match station needs at least 2 inputs" }
    }

    private var myNextReceiver: QObjectReceiverIfc = nextReceiver

    /** Sets the receiver of combined instances. */
    fun nextReceiver(receiver: QObjectReceiverIfc) {
        myNextReceiver = receiver
    }

    private fun keyOf(qObject: QObject): Any = keyExtractor?.invoke(qObject) ?: UNKEYED_MATCH_KEY

    // per-input: key -> FIFO of waiting instances with that key
    private val myWaiting: List<LinkedHashMap<Any, ArrayDeque<QObject>>> =
        (0 until numInputs).map { LinkedHashMap() }

    private inner class InputReceiver(val index: Int) : QObjectReceiverIfc {
        override fun receive(arrivingQObject: QObject) {
            val key = keyOf(arrivingQObject)
            myWaiting[index].getOrPut(key) { ArrayDeque() }.addLast(arrivingQObject)
            myNumWaiting.increment()
            tryMatch(key)
        }
    }

    private val myInputs: List<InputReceiver> = (0 until numInputs).map { InputReceiver(it) }

    /** Returns the receiver for input [index]. */
    fun input(index: Int): QObjectReceiverIfc = myInputs[index]

    private val myNumWaiting = TWResponse(this, "${this.name}:NumWaiting")
    override val numWaiting: TWResponseCIfc
        get() = myNumWaiting

    private val myNumMatched = Counter(this, "${this.name}:NumMatched")
    override val numMatched: CounterCIfc
        get() = myNumMatched

    override fun outlets(): List<QObjectReceiverIfc> =
        if (myNextReceiver === NotImplementedReceiver) emptyList() else listOf(myNextReceiver)

    override val hasOnwardRouting: Boolean
        get() = myNextReceiver !== NotImplementedReceiver

    private fun hasKeyOnEveryInput(key: Any): Boolean =
        myWaiting.all { (it[key]?.isNotEmpty()) == true }

    private fun tryMatch(key: Any) {
        while (hasKeyOnEveryInput(key)) {
            val members = myWaiting.map { it[key]!!.removeFirst() }
            myNumWaiting.decrement(numInputs.toDouble())
            val product = QObject()
            product.attachedObject = members
            myNumMatched.increment()
            myNextReceiver.receive(product)
        }
    }

    override fun initialize() {
        myWaiting.forEach { it.clear() }
    }
}
