package ksl.simulation

import jsl.simulation.Simulation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {} //TODO decide if this should be KSL or not Simulation logger

class Model internal constructor(
    theSimulation: Simulation,
    theExecutive: Executive = Executive(),
    name: String = theSimulation.name + "_Model"
) : ModelElement(name) {

    internal val mySimulation: Simulation = theSimulation
    internal val myExecutive: Executive = theExecutive

    var baseTimeUnit: TimeUnit = TimeUnit.MILLISECOND

    /**
     * A Map that holds all the model elements in the order in which they are
     * created
     */
    private val myModelElementMap: Map<String, ModelElement> = LinkedHashMap()

    init {
        myModel = this
        myParentModelElement = null
        //TODO a whole lot to do
    }

    internal fun addToModelElementMap(modelElement: ModelElement) {
        TODO("Not implemented yet")
    }


    /**
     * Schedules the ending of the executive at the provided time
     *
     * @param time the time of the ending event, must be &gt; 0
     */
    internal fun scheduleEndEvent(time: Double) {
        require(time > 0.0) { "The time must be > 0.0" }
        if (executive.isEndEventScheduled()) {
            logger.info { "Model: Already scheduled end of replication event is being cancelled" }
            // already scheduled end event, cancel it
            executive.endEvent!!.cancelled = true
        }
        // schedule the new time
        logger.info { "Model: scheduling end of replication at time: $time" }
        executive.endEvent = EndEventAction().schedule(time)
    }

    private inner class EndEventAction : EventAction<Nothing>() {
        override fun action(event: JSLEvent<Nothing>) {
            executive.stop("Executive: Scheduled end event occurred at time $time")
        }

        fun schedule(time: Double): JSLEvent<Nothing> {
            return schedule(time, JSLEvent.DEFAULT_END_REPLICATION_EVENT_PRIORITY, name = "End Replication")
        }
    }
}

fun main() {
    val m = Model(Simulation())

    val me = ModelElement(m, "something")

    println(m)
    println(me)
}