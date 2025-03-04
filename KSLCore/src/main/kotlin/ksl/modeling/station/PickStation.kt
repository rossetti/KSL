package ksl.modeling.station

import ksl.simulation.ModelElement

class PickStationReceiver(
    var stations: List<Station>,
    var comparator: Comparator<Station> = StationWIPComparator()
) : QObjectReceiverIfc {
    override fun receive(arrivingQObject: ModelElement.QObject) {
        val picked = stations.minWith(comparator)
        picked.receive(arrivingQObject)
    }
}

class PickStationSender(
    var stations: List<Station>,
    var comparator: Comparator<Station> = StationWIPComparator()
) : QObjectSender() {

    override fun selectNextReceiver(): QObjectReceiverIfc {
        return stations.minWith(comparator)
    }

}