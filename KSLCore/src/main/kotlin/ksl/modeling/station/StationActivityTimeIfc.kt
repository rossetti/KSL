package ksl.modeling.station

import ksl.simulation.ModelElement.QObjectIfc

fun interface StationActivityTimeIfc {

    fun activityTime(qObjectIfc: QObjectIfc, station: Station) : Double

}