package ksl.modeling.variable

class MaxResponse(observedResponse: Response) : Response(observedResponse, name = "${observedResponse.name}:Max") {

    private val response = observedResponse

    override fun replicationEnded() {
        this.value = response.withinReplicationStatistic.max
    }
}