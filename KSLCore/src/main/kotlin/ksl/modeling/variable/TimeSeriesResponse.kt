package ksl.modeling.variable

import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

class TimeSeriesResponse(
    parent: ModelElement,
    periodLength: Double,
    responses: Set<Response> = emptySet(),
    counters: Set<Counter> = emptySet(),
    var autoStart: Boolean = true,
    name: String? = null
) : ModelElement(parent, name) {

    init {
        require(periodLength.isFinite()) {"The length of the time series period must be finite"}
        require(periodLength > 0.0) {"The length of the time series period must be > 0.0"}
        require(responses.isNotEmpty() || counters.isNotEmpty()) {"Both the responses set and the counter set were empty."}
    }

    fun startCollection(startTime: Double){
        require(startTime >= 0.0) {"The start time must be >= 0.0"}
        schedule(fi)
    }

    private fun startFirstPeriod(event: KSLEvent<Nothing>){

    }

    private fun endPeriodEvent(event: KSLEvent<Nothing>){

    }
}