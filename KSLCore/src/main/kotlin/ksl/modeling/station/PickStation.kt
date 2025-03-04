package ksl.modeling.station

import ksl.simulation.ModelElement

/**
 *  Picks the minimum from the list of stations based on the comparator.
 *  Causes the arriving qObject to be received at the picked station.
 */
class PickStationReceiver(
    var stations: List<Station>,
    var comparator: Comparator<Station> = StationWIPComparator()
) : QObjectReceiverIfc {
    override fun receive(arrivingQObject: ModelElement.QObject) {
        val picked = stations.minWith(comparator)
        picked.receive(arrivingQObject)
    }
}


/**
 *  Picks the minimum from the list of stations based on the comparator.
 */
class PickStationSender(
    var stations: List<Station>,
    var comparator: Comparator<Station> = StationWIPComparator()
) : QObjectSender() {

    override fun selectNextReceiver(): QObjectReceiverIfc {
        return stations.minWith(comparator)
    }

}