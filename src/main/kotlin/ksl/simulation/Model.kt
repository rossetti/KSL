package ksl.simulation

//import jsl.simulation.Simulation //TODO
import ksl.calendar.CalendarIfc
import ksl.calendar.PriorityQueueEventCalendar
import mu.KotlinLogging

private val logger = KotlinLogging.logger {} //TODO decide if this should be KSL or not Simulation logger

class Model internal constructor(
    theSimulation: Simulation,
    eventCalendar: CalendarIfc = PriorityQueueEventCalendar(),
    name: String = theSimulation.name + "_Model"
) : ModelElement(name) {

    internal val mySimulation: Simulation = theSimulation
    internal val myExecutive: Executive = Executive(eventCalendar)

    var baseTimeUnit: TimeUnit = TimeUnit.MILLISECOND

    /**
     * A Map that holds all the model elements in the order in which they are
     * created
     */
    private val myModelElementMap: MutableMap<String, ModelElement> = LinkedHashMap()

    init {
        myModel = this
        myParentModelElement = null
        addToModelElementMap(this)
        addDefaultElements()
    }

    private fun addDefaultElements() {
       //TODO  myDefaultEntityType = EntityType(this, "DEFAULT_ENTITY_TYPE")
        println("Need to add default elements in Model!")
    }

    /**
     * Removes the given model element from the Model's model element map. Any
     * child model elements of the supplied model element are also removed from
     * the map, until all elements below the given model element are removed.
     *
     * @param modelElement
     */
    internal fun removeFromModelElementMap(modelElement: ModelElement) {

        if (myModelElementMap.containsKey(modelElement.name)) {
            //	remove the associated model element from the map, if there
            myModelElementMap.remove(modelElement.name)
            //TODO revisit when containers are added
//            if (modelElement is ResponseVariable) {
//                myResponseVariables.remove(modelElement as ResponseVariable)
//            }
//            if (modelElement is Counter) {
//                myCounters.remove(modelElement as Counter)
//            }
//            if (modelElement is RandomElementIfc) {
//                myRandomElements.remove(modelElement as RandomElementIfc)
//            }
//            if (modelElement is Variable) {
//                if (Variable::class.java == modelElement.javaClass) {
//                    myVariables.remove(modelElement as Variable)
//                }
//            }
            modelElement.currentStatus = Status.MODEL_ELEMENT_REMOVED

            // remove any of the modelElement's children and so forth from the map
            val i: Iterator<ModelElement> = modelElement.getChildModelElementIterator()
            var m: ModelElement
            while (i.hasNext()) {
                m = i.next()
                removeFromModelElementMap(m)
            }
        }
    }

    internal fun addToModelElementMap(modelElement: ModelElement) {

        if (simulation.isRunning) {
            val sb = StringBuilder()
            sb.append("Attempted to add the model element: ")
            sb.append(modelElement.name)
            sb.append(" while the simulation was running.")
            logger.error{sb.toString()}
            throw IllegalStateException(sb.toString())
        }

        if (myModelElementMap.containsKey(modelElement.name)) {
            val sb = StringBuilder()
            sb.append("A ModelElement with the name: ")
            sb.append(modelElement.name)
            sb.append(" has already been added to the Model.")
            sb.appendLine()
            sb.append("Every model element must have a unique name!")
            logger.error(sb.toString())
            throw IllegalArgumentException(sb.toString())
        }

        myModelElementMap[modelElement.name] = modelElement

        //TODO need to add the containers for these

//        if (modelElement is ResponseVariable) {
//            myResponseVariables.add(modelElement as ResponseVariable)
//        }
//
//        if (modelElement is Counter) {
//            myCounters.add(modelElement as Counter)
//        }
//
//        if (modelElement is RandomElementIfc) {
//            myRandomElements.add(modelElement as RandomElementIfc)
//        }
//
//        if (modelElement is Variable) {
//            if (Variable::class.java == modelElement.javaClass) {
//                myVariables.add(modelElement as Variable)
//            }
//        }

        modelElement.currentStatus = Status.MODEL_ELEMENT_ADDED
    }

    /**
     * Checks to see if the model element has been registered with the Model
     * using it's uniquely assigned name.
     *
     * @param modelElementName the name of the model element to check
     * @return true if contained
     */
    fun containsModelElement(modelElementName: String): Boolean {
        return myModelElementMap.containsKey(modelElementName)
    }

    /**
     * Returns the model element associated with the name. In some sense, this
     * model violates encapsulation for ModelElements, but since the client
     * knows the unique name of the ModelElement, we assume that the client
     * knows what they are doing.
     *
     * @param name The name of the model element, the name must not be null
     * @return The named ModelElement if it exists in the model by the supplied
     * name, null otherwise
     */
    fun getModelElement(name: String): ModelElement? {
        return if (!myModelElementMap.containsKey(name)) {
            null
        } else myModelElementMap[name]
    }

    /**
     * Returns the model element that has the provided unique id or null if not
     * found
     *
     * @param id the identifier getId() of the desired ModelElement
     * @return the ModelElement
     */
    fun getModelElement(id: Int): ModelElement? {
        for (entry in myModelElementMap.entries.iterator()){
            if (entry.value.id == id){
                return entry.value
            }
        }
        return null
    }

    /**
     * Schedules the ending of the executive at the provided time
     *
     * @param time the time of the ending event, must be &gt; 0
     */
    internal fun scheduleEndEvent(time: Double) {
        require(time > 0.0) { "The time must be > 0.0" }
        if (executive.isEndEventScheduled()) {
            logger.info { "Model: Already scheduled end of replication event for time = ${executive.endEvent!!.time} is being cancelled" }
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
            return schedule(time, null, JSLEvent.DEFAULT_END_REPLICATION_EVENT_PRIORITY, name = "End Replication")
        }
    }

    /** Counts the number of pre-order traversals of the model element tree and
     * labels each model element with the appropriate left and right traversal
     * count.  Called from Simulation in ReplicationExecutionProcess.initializeIterations()
     *
     * @return the number of traversals in the model element hierarchy
     */
    private fun markPreOrderTraversalModelElementHierarchy() {
        markPreOrderTraversalTree(0)
    }

    //called from simulation, so internal
    internal fun setUpExperiment() {
        executive.terminationWarningMsgOption = false
        markPreOrderTraversalModelElementHierarchy()
        // already should have reference to simulation

        TODO("setUpExperiment() not implemented yet!")

    }

    internal fun runReplication(){
        simulation.incrementCurrentReplicationNumber()
        if (simulation.maximumAllowedExecutionTimePerReplication > 0) {
            executive.maximumAllowedExecutionTime = simulation.maximumAllowedExecutionTimePerReplication
        }
        executive.initialize()
       //TODO model.setUpReplication(this@Simulation)
        executive.executeAllEvents()

        //            if (maximumAllowedExecutionTimePerReplication > 0) {
//                executive.maximumAllowedExecutionTime = maximumAllowedExecutionTimePerReplication
//            }
//            executive.initialize()
//            model.setUpReplication(this@Simulation)
//            executive.executeAllEvents()
//            model.afterReplication(myExperiment)
    }

    internal fun endExperiment(){

        TODO("endExperiment() not implemented yet!")
    }

}

fun main() {
    val m = Model(Simulation())

    val me = ModelElement(m, "something")

    println(m)
    println(me)
}