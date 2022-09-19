package ksl.modeling.variable

import ksl.simulation.ModelElement

class ResponseSchedule(
    parent: ModelElement,
    theStartTime: Double = 0.0,
    repeatSchedule: Boolean = true,
    name: String? = null
) : ModelElement(parent, name) {

    internal fun responseIntervalEnded(responseInterval: ResponseInterval) {
        TODO("Not implemented yet")
    }
}