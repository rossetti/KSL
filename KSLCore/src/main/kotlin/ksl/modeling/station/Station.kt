package ksl.modeling.station

import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.*
import ksl.simulation.ModelElement

interface StationCIfc {
    val numAtStation: TWResponseCIfc
    val stationTime: ResponseCIfc
    val numProcessed: CounterCIfc
}

interface ActivityStationCIfc : StationCIfc {
    val activityTimeRV: RandomSourceCIfc
}

interface SingleQStationCIfc : ActivityStationCIfc {
    val resource: SResourceCIfc

    val waitingQ: QueueCIfc<ModelElement.QObject>

    /**
     *  Indicates if the resource has units available.
     */
    val isResourceAvailable: Boolean

    /**
     *  Indicates if the queue is empty.
     */
    val isQueueEmpty: Boolean

    /**
     * Indicates if the queue is not empty
     */
    val isQueueNotEmpty: Boolean
}

/**
 *  A station is a location that can receive, potentially
 *  process instances of the QObject class, and cause them to
 *  be received.
 */
abstract class Station(
    parent: ModelElement,
    var nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : ModelElement(parent, name), QObjectReceiverIfc, StationCIfc {

    protected val myNS: TWResponse = TWResponse(this, "${this.name}:NS")
    override val numAtStation: TWResponseCIfc
        get() = myNS

    protected val myStationTime: Response = Response(this, "${this.name}:StationTime")
    override val stationTime: ResponseCIfc
        get() = myStationTime

    protected val myNumProcessed: Counter = Counter(this, "${this.name}:NumProcessed")
    override val numProcessed: CounterCIfc
        get() = myNumProcessed

    /**
     * A QObject may or may not have a helper object that implements the
     * QObjectSenderIfc interface.  If this helper object is supplied it will
     * be used to send the processed QObject to its next location for
     * processing.
     *
     * If the QObject does not have the helper object, then the nextReceiver
     * property is used to determine the next location for the qObject.
     *
     * If neither helper object is supplied then the station quietly does nothing
     * with the qObject.
     *
     * @param completedQObject the completed QObject
     */
    protected fun sendToNextReceiver(completedQObject: QObject) {
        onExit(completedQObject)
        if (completedQObject.sender != null){
            completedQObject.sender!!.send()
        } else {
            nextReceiver.receive(completedQObject)
        }
    }

    protected fun departureCollection(completedQObject: QObject){
        myNS.decrement() // qObject completed
        myNumProcessed.increment()
        myStationTime.value = (time - completedQObject.timeStamp)
    }

    protected fun arrivalCollection(arrivingQObject: QObject){
        arrivingQObject.currentReceiver = this
        myNS.increment() // new qObject arrived
        arrivingQObject.timeStamp = time
    }

    override fun receive(arrivingQObject: QObject) {
        onEntry(arrivingQObject)
    }

    /**
     *  This function can be overridden to provide logic
     *  upon entry to the station before any other state
     *  changes occur.
     */
    open fun onEntry(arrivingQObject: QObject){
        arrivalCollection(arrivingQObject)
    }

    /**
     *  This function can be overridden to provide logic
     *  upon exit from the station before being sent to the
     *  next receiver.
     */
    open fun onExit(completedQObject: QObject){
        departureCollection(completedQObject)
    }
}
