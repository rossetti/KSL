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

/** Read-only view of a [BatchStation]. */
interface BatchStationCIfc {
    /** Time-weighted number of instances currently accumulating toward a batch. */
    val numAccumulating: TWResponseCIfc

    /** The number of batches formed. */
    val numBatchesFormed: CounterCIfc
}

/**
 *  Accumulates arriving QObject instances until [batchSize] have been collected,
 *  then forms a single batch QObject (carrying the members as its attached object)
 *  and sends it onward. A [SeparateStation] is the inverse, recovering the members.
 *
 *  The batch wrapper is a new instance and is not counted in the owning network's
 *  number-in-system; the original members carry that accounting, so batching then
 *  separating is population-neutral. Members left accumulating at the end of a
 *  replication are simply not batched.
 *
 *  @param parent the model element serving as this station's parent
 *  @param batchSize the number of instances per batch (>= 1)
 *  @param nextReceiver where formed batches are sent
 *  @param name the name of the station
 */
class BatchStation(
    parent: ModelElement,
    val batchSize: Int,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : ModelElement(parent, name), QObjectReceiverIfc, RoutingOutletsIfc, BatchStationCIfc {
    init {
        require(batchSize >= 1) { "The batch size must be >= 1" }
    }

    private var myNextReceiver: QObjectReceiverIfc = nextReceiver

    /** Sets the receiver of formed batches. */
    fun nextReceiver(receiver: QObjectReceiverIfc) {
        myNextReceiver = receiver
    }

    private val myAccumulating = mutableListOf<QObject>()

    private val myNumAccumulating = TWResponse(this, "${this.name}:NumAccumulating")
    override val numAccumulating: TWResponseCIfc
        get() = myNumAccumulating

    private val myNumBatched = Counter(this, "${this.name}:NumBatchesFormed")
    override val numBatchesFormed: CounterCIfc
        get() = myNumBatched

    override fun outlets(): List<QObjectReceiverIfc> =
        if (myNextReceiver === NotImplementedReceiver) emptyList() else listOf(myNextReceiver)

    override val hasOnwardRouting: Boolean
        get() = myNextReceiver !== NotImplementedReceiver

    override fun receive(arrivingQObject: QObject) {
        myAccumulating.add(arrivingQObject)
        myNumAccumulating.value = myAccumulating.size.toDouble()
        if (myAccumulating.size >= batchSize) {
            val members = myAccumulating.toList()
            myAccumulating.clear()
            myNumAccumulating.value = 0.0
            val batch = QObject()
            batch.attachedObject = members
            myNumBatched.increment()
            myNextReceiver.receive(batch)
        }
    }

    override fun initialize() {
        myAccumulating.clear()
    }
}
