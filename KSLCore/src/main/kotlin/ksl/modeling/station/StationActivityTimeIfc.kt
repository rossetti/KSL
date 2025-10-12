package ksl.modeling.station

import ksl.simulation.ModelElement.QObjectIfc

/**
 *  A functional interface for determining the activity time at a station
 */
fun interface StationActivityTimeIfc {

    fun activityTime(qObjectIfc: QObjectIfc, station: Station) : Double

}