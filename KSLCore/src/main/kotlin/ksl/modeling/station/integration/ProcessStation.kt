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

package ksl.modeling.station.integration

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.station.*
import ksl.modeling.variable.*
import ksl.simulation.ModelElement

/**
 *  A station whose "activity" is an arbitrary process-view [KSLProcess] rather than
 *  a fixed delay — the delegate-and-continue generalization of an activity station.
 *
 *  On receiving an instance, the station spawns a transient carrier [ProcessModel.Entity]
 *  that runs the supplied [activity] (which may seize resources, delay, move, hold, or
 *  anything the process view supports). When the process completes, the original
 *  instance is sent onward. Activation is non-suspending, so the station's flow is
 *  driven by events while the coroutine work happens inside the carrier's process.
 *
 *  This is an integration adapter: it depends on the process view (`ksl.modeling.entity`)
 *  and lives in the integration package so the core station package stays coroutine-free.
 *
 *  Create one via the [processStation] extension on [StationNetwork].
 *
 *  @param parent the model element serving as this station's parent (typically the network)
 *  @param activity the process to run for each instance; receives the instance
 *  @param nextReceiver where instances are sent after their process completes
 *  @param name the name of the station
 */
class ProcessStation(
    parent: ModelElement,
    private val activity: suspend KSLProcessBuilder.(QObject) -> Unit,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : ProcessModel(parent, name), QObjectReceiverIfc, RoutingOutletsIfc {

    private var myNextReceiver: QObjectReceiverIfc = nextReceiver

    /** Sets the receiver of instances whose process has completed. */
    fun nextReceiver(receiver: QObjectReceiverIfc) {
        myNextReceiver = receiver
    }

    private val myNumInStation = TWResponse(this, "${this.name}:NumInStation")
    val numInStation: TWResponseCIfc
        get() = myNumInStation

    private val myStationTime = Response(this, "${this.name}:StationTime")
    val stationTime: ResponseCIfc
        get() = myStationTime

    private val myNumProcessed = Counter(this, "${this.name}:NumProcessed")
    val numProcessed: CounterCIfc
        get() = myNumProcessed

    override fun outlets(): List<QObjectReceiverIfc> =
        if (myNextReceiver === NotImplementedReceiver) emptyList() else listOf(myNextReceiver)

    override val hasOnwardRouting: Boolean
        get() = myNextReceiver !== NotImplementedReceiver

    private inner class Carrier(val item: QObject) : Entity() {
        val script: KSLProcess = process { activity(item) }

        override fun afterRunningProcess(completedProcess: KSLProcess) {
            complete(item)
        }
    }

    override fun receive(arrivingQObject: QObject) {
        arrivingQObject.stationArriveTime = time
        myNumInStation.increment()
        val carrier = Carrier(arrivingQObject)
        activate(carrier.script)
    }

    private fun complete(item: QObject) {
        myNumInStation.decrement()
        myNumProcessed.increment()
        myStationTime.value = time - item.stationArriveTime
        myNextReceiver.receive(item)
    }
}

/**
 *  Creates a [ProcessStation] in this network whose activity is the supplied process
 *  [activity], registered under [name]. Wire upstream nodes to it like any receiver.
 */
fun StationNetwork.processStation(
    name: String,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    activity: suspend KSLProcessBuilder.(ModelElement.QObject) -> Unit
): ProcessStation {
    val ps = ProcessStation(this, activity, nextReceiver, "${this.name}:$name")
    this.register(name, ps)
    return ps
}
