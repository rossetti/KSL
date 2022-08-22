package ksl.simulation

import jsl.utilities.random.rvariable.RVParameterSetter//TODO
import kotlinx.datetime.Clock
import ksl.calendar.CalendarIfc
import ksl.calendar.PriorityQueueEventCalendar
import ksl.controls.Controls
import ksl.modeling.elements.RandomElementIfc
import ksl.modeling.entity.EntityType
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.modeling.variable.Variable
import ksl.utilities.io.KSL
import ksl.utilities.io.LogPrintWriter
import ksl.utilities.io.OutputDirectory
import mu.KLoggable
import java.nio.file.Path

private var simCounter: Int = 0

class Model internal constructor(
    simulationName: String = "Simulation${++simCounter}",
    pathToOutputDirectory: Path = KSL.createSubDirectory(simulationName + "_OutputDir"),
    eventCalendar: CalendarIfc = PriorityQueueEventCalendar(),
) : ModelElement("MainModel"), ExperimentIfc {
//TODO what are the public methods/properties of ModelElement and are they all appropriate for Model
//TODO statistical batching, but move it within Model
//TODO observers
//TODO note that JSLDataBaseObserver is actually attached as an observer on Model
//TODO controls and parameters
//TODO simulation reporter
    /**
     *
     * @return the defined OutputDirectory for the simulation
     */
    val outputDirectory: OutputDirectory = OutputDirectory(pathToOutputDirectory, "kslOutput.txt")

    /**
     *
     * @return the pre-defined default text output file for the simulation
     */
    val out: LogPrintWriter
        get() = outputDirectory.out

    internal val myExecutive: Executive = Executive(eventCalendar)
    internal val myExperiment: Experiment = Experiment()


    /** A flag to control whether a warning is issued if the user does not
     * set the replication run length
     */
    var repLengthWarningMessageOption = true

    /**
     *  The base time units for the simulation model. By default, this is 1.0.
     */
    var baseTimeUnit: TimeUnit = TimeUnit.MILLISECOND

    private var myResponseVariables: MutableList<Response> = ArrayList()

    /**
     * A list of all the Counters within the model
     */
    private var myCounters: MutableList<Counter> = ArrayList()

    /**
     * A list of all the Variables within the model
     */
    private var myVariables: MutableList<Variable> = ArrayList()

    /**
     * A list of all random elements within the model
     */
    private var myRandomElements: MutableList<RandomElementIfc> = ArrayList()

    /**
     * A Map that holds all the model elements in the order in which they are
     * created
     */
    private val myModelElementMap: MutableMap<String, ModelElement> = LinkedHashMap()

    /** to hold the controls if used
     *
     */
    private lateinit var myControls: Controls

    /**
     * to hold the parameters of the random variables if used
     */
    private val myRVParameterSetter: RVParameterSetter? = null

    /**
     * Controls the execution of replications
     */
    private val myReplicationProcess: ReplicationProcess = ReplicationProcess("Model: Replication Process")

    internal lateinit var myDefaultEntityType: EntityType

    init {
        myModel = this
        myParentModelElement = null
        addToModelElementMap(this)
        addDefaultElements()
    }

    /**
     * A flag to indicate whether the simulation is done A simulation can be done if:
     * 1) it ran all of its replications 2) it was ended by a
     * client prior to completing all of its replications 3) it ended because it
     * exceeded its maximum allowable execution time before completing all of
     * its replications. 4) its end condition was satisfied
     *
     */
    val isDone: Boolean
        get() = myReplicationProcess.isDone

    /**
     * Returns if the elapsed execution time exceeds the maximum time allowed.
     * Only true if the maximum was set and elapsed time is greater than or
     * equal to maximumAllowedExecutionTime
     */
    val isExecutionTimeExceeded: Boolean
        get() = myReplicationProcess.isExecutionTimeExceeded

    /**
     * Returns system time in nanoseconds that the simulation started
     */
    val beginExecutionTime: Long
        get() = myReplicationProcess.beginExecutionTime

    /**
     * Gets the clock time in nanoseconds since the simulation was
     * initialized
     */
    val elapsedExecutionTime: Long
        get() = myReplicationProcess.elapsedExecutionTime

    /**
     * Returns system time in nanoseconds that the simulation ended
     */
    val endExecutionTime: Long
        get() = myReplicationProcess.endExecutionTime

    /**
     * The maximum allotted (suggested) execution (real) clock for the
     * entire iterative process in nanoseconds. This is a suggested time because the execution
     * time requirement is only checked after the completion of an individual
     * step After it is discovered that cumulative time for executing the step
     * has exceeded the maximum time, then the iterative process will be ended
     * (perhaps) not completing other steps.
     */
    var maximumAllowedExecutionTime: Long
        get() = myReplicationProcess.maximumAllowedExecutionTime
        set(value) {
            myReplicationProcess.maximumAllowedExecutionTime = value
        }

    /**
     * Returns the replications completed since the simulation was
     * last initialized
     *
     * @return the number of replications completed
     */
    val numberReplicationsCompleted: Int
        get() = myReplicationProcess.numberStepsCompleted

    /**
     * Checks if the simulation is in the created state. If the
     * simulation is in the created execution state this method will return true
     *
     * @return true if in the created state
     */
    val isCreated: Boolean
        get() = myReplicationProcess.isCreated

    /**
     * Checks if the simulation is in the initialized state After the
     * simulation has been initialized this method will return true
     *
     * @return true if initialized
     */
    val isInitialized: Boolean
        get() = myReplicationProcess.isInitialized

    /**
     * A simulation is running if it has been told to run (i.e.
     * run() or runNextReplication()) but has not yet been told to end().
     *
     */
    val isRunning: Boolean
        get() = myReplicationProcess.isRunning

    /**
     * Checks if the simulation is in the completed step state After the
     * simulation has successfully completed a replication this property will be true
     */
    val isReplicationCompleted: Boolean
        get() = myReplicationProcess.isStepCompleted

    /**
     * Checks if the simulation is in the ended state. After the simulation has been ended this property will return true
     */
    val isEnded: Boolean
        get() = myReplicationProcess.isEnded

    /**
     * The simulation may end by a variety of means, this  checks
     * if the simulation ended because it ran all of its replications, true if all completed
     */
    val allReplicationsCompleted: Boolean
        get() = myReplicationProcess.allStepsCompleted

    /**
     * The simulation may end by a variety of means, this method checks
     * if the simulation ended because it was stopped, true if it was stopped via stop()
     */
    val stoppedByCondition: Boolean
        get() = myReplicationProcess.stoppedByCondition

    /**
     * The simulation may end by a variety of means, this method checks
     * if the simulation ended but was unfinished, not all replications were completed.
     */
    val isUnfinished: Boolean
        get() = myReplicationProcess.isUnfinished


    //TODO revisit myDefaultEntityType when working on process modeling
    private fun addDefaultElements() {
        myDefaultEntityType = EntityType(this, "DEFAULT_ENTITY_TYPE")
    }

    /**
     *  Causes the specified model element to be removed from the model regardless of
     *  its location within the model hierarchy. Any model elements attached to the
     *  supplied model element will also be removed.
     *
     * Recursively removes the model element and the children of the model
     * element and all their children, etc. The children will no longer have a
     * parent and will no longer have a model.  This can only be done when
     * the simulation that contains the model is not running.
     *
     * This method has very serious side effects. After invoking this method:
     *
     * 1) All children of the model element will have been removed from the
     * model.
     * 2) The model element will be removed from its parent's model,
     * element list and from the model. The getParentModelElement() method will
     * return null. In other words, the model element will no longer be connected
     * to a parent model element.
     * 3) The model element and all its children will no longer be
     * connected. In other words, there is no longer a parent/child relationship
     * between the model element and its former children.
     * 4) This model element and all of its children will no longer belong to a model.
     * 5) The removed elements are no longer part of their former model's model element map
     * 6) Warm up and timed update listeners are set to null
     * 7) Any reference to a spatial model is set to null
     * 8) All observers of the model element are detached
     * 9) All child model elements are removed. It will no longer have any children.
     *
     * Since it has been removed from the model, it and its children will no
     * longer participate in any of the standard model element actions, e.g.
     * initialize(), afterReplication(), etc.
     *
     *
     * Notes: 1) This method removes from the list of model elements. Thus, if a
     * client attempts to use this method, via code that is iterating the list a
     * concurrent modification exception will occur.
     * 2) The user is responsible for ensuring that other references to the model
     * element are correctly handled.  If references to the model element exist within
     * other data structures/collections then the user is responsible for appropriately
     * addressing those references. This is especially important for any observers
     * of the removed model element.  The observers will be notified that the model
     * element is being removed. It is up to the observer to correctly react to
     * the removal. If the observer is a subclass of ModelElementObserver then
     * implementing the removedFromModel() method can be used. If the observer is a
     * general Observer, then use REMOVED_FROM_MODEL to check if the element is being removed.
     */
    fun removeFromModel(element: ModelElement) {
        element.removeFromModel()
    }

    /**
     * Causes RandomElementIfc that have been added to the model to immediately
     * turn on their antithetic generating streams.
     */
    fun turnOnAntithetic() {
        for (rv in myRandomElements) {
            rv.antithetic = true
        }
    }

    /**
     * Causes RandomElementIfc that have been added to the model to immediately
     * turn off their antithetic generating streams.
     */
    fun turnOffAntithetic() {
        for (rv in myRandomElements) {
            rv.antithetic = false
        }
    }

    /**
     * Advances the streams of all RandomElementIfc n times. If n &lt;= 0, no
     * advancing occurs
     *
     * @param n the number of times to advance
     */
    fun advanceSubStreams(n: Int) {
        if (n <= 0) {
            return
        }
        for (i in 1..n) {
            advanceToNextSubStream()
        }
    }

    /**
     * Causes RandomElementIfc that have been added to the model to immediately
     * advance their random number streams to the next sub-stream in their
     * stream.
     */
    fun advanceToNextSubStream() {
        for (rv in myRandomElements) {
            rv.advanceToNextSubStream()
        }
    }

    /**
     * Causes RandomElementIfc that have been added to the model to immediately
     * reset their random number streams to the beginning of their starting
     * stream.
     */
    fun resetStartStream() {
        for (rv in myRandomElements) {
            rv.resetStartStream()
        }
    }

    /**
     * Causes RandomElementIfc that have been added to the model to immediately
     * reset their random number streams to the beginning of their current sub
     * stream.
     */
    fun resetStartSubStream() {
        for (rv in myRandomElements) {
            rv.resetStartSubStream()
        }
    }

    /**
     *
     * @return the controls for the model
     */
    fun getControls(): Controls {
        if (!::myControls.isInitialized) {
            myControls = Controls(this)
        }
        return myControls
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
            if (modelElement is Response) {
                myResponseVariables.remove(modelElement)
            }
            if (modelElement is Counter) {
                myCounters.remove(modelElement)
            }
            if (modelElement is RandomElementIfc) {
                myRandomElements.remove(modelElement as RandomElementIfc)
            }
            if (modelElement is Variable) {
                if (Variable::class == modelElement::class) {//TODO not 100% sure if only super type is removed
                    myVariables.remove(modelElement)
                }
            }
            modelElement.currentStatus = Status.MODEL_ELEMENT_REMOVED
            logger.trace { "Model: Removed model element ${modelElement.name} from the model with parent ${modelElement.myParentModelElement?.name}" }
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

        if (isRunning) {
            val sb = StringBuilder()
            sb.append("Attempted to add the model element: ")
            sb.append(modelElement.name)
            sb.append(" while the simulation was running.")
            logger.error { sb.toString() }
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
        logger.trace { "Added model element ${modelElement.name} to the model with parent ${modelElement.myParentModelElement?.name}" }

        if (modelElement is Response) {
            myResponseVariables.add(modelElement)
        }

        if (modelElement is Counter) {
            myCounters.add(modelElement)
        }

        if (modelElement is RandomElementIfc) {
            myRandomElements.add(modelElement as RandomElementIfc)
        }

        if (modelElement is Variable) {
            if (Variable::class == modelElement::class) {//TODO not 100% sure if only super type is removed
                myVariables.add(modelElement)
            }
        }

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
        for (entry in myModelElementMap.entries.iterator()) {
            if (entry.value.id == id) {
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
    internal fun scheduleEndOfReplicationEvent(time: Double) {
        require(time > 0.0) { "The time must be > 0.0" }
        if (executive.isEndEventScheduled()) {
            logger.info { "Already scheduled end of replication event for time = ${executive.endEvent!!.time} is being cancelled" }
            // already scheduled end event, cancel it
            executive.endEvent!!.cancelled = true
        }
        // schedule the new time
        if (time.isFinite()) {
            logger.info { "Scheduling end of replication at time: $time" }
            executive.endEvent = EndEventAction().schedule(time)
        } else {
            logger.info { "Did not schedule end of replication event because time was $time" }
        }

    }

    private inner class EndEventAction : EventAction<Nothing>() {
        override fun action(event: JSLEvent<Nothing>) {
            executive.stop("Scheduled end event occurred at time $time")
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

    private fun setUpReplication() {

        // remove any marked model elements were added during previous replication
//        removeMarkedModelElements();

        // setup warm up period
        lengthOfWarmUp = lengthOfReplicationWarmUp

        // control streams for antithetic option
        handleAntitheticReplications()

        // do all model element beforeReplication() actions
        logger.info { "Executing before replication actions for model elements" }
        beforeReplicationActions()

        // schedule the end of the replication
        scheduleEndOfReplicationEvent(lengthOfReplication)

        // if necessary, initialize the model elements
        if (replicationInitializationOption) {
            // initialize the model and all model elements with initialize option on
            logger.info { "Executing initialize() actions for model elements" }
            initializeActions()
        }

        // allow model elements to register conditional actions
        logger.info { "Registering conditional actions for model elements" }
        registerConditionalActionsWithExecutive()

        // if monte carlo option is on, call the model element's monteCarlo() methods
        if (monteCarloOption) {
            // since monte carlo option was turned on, assume everyone wants to listen
            setMonteCarloOptionForModelElements(true)
            logger.info { "Executing monteCarloActions() actions for model elements" }
            monteCarloActions()
        }
    }

    private fun handleAntitheticReplications() {
        // handle antithetic replications
        if (antitheticOption) {
            logger.info { "Executing handleAntitheticReplications() setup" }
            if (currentReplicationNumber % 2 == 0) {
                // even number replication
                // return to beginning of sub-stream
                resetStartSubStream()
                // turn on antithetic sampling
                turnOnAntithetic()
            } else  // odd number replication
                if (currentReplicationNumber > 1) {
                    // turn off antithetic sampling
                    turnOffAntithetic()
                    // advance to next sub-stream
                    advanceToNextSubStream()
                }
        }
    }

    /**
     * Sets the reset start stream option for all RandomElementIfc in the model
     * to the supplied value, true is the default behavior. This method is used
     * by an experiment prior to beforeExperimentActions() being called Thus, any
     * RandomElementIfc must already have been created and attached to the model
     * elements
     *
     * @param option The option, true means to reset prior to each experiment
     */
    private fun setAllRVResetStartStreamOptions(option: Boolean) {
        for (rv in myRandomElements) {
            rv.resetStartStreamOption = option
        }
    }

    /**
     * Sets the reset next sub stream option for all RandomElementIfc in the
     * model to the supplied value, true is the default behavior. True implies
     * that the sub-streams will be advanced at the end of the replication. This
     * method is used by an experiment prior to beforeExperimentActions() being called
     * Thus, any RandomElementIfc must already have been created and attached to
     * the model elements
     *
     * @param option The option, true means to reset prior to each replication
     */
    private fun setAllRVResetNextSubStreamOptions(option: Boolean) {
        for (rv in myRandomElements) {
            rv.resetNextSubStreamOption = option
        }
    }

    //called from simulation, so internal
    internal fun setUpExperiment() {
        logger.info { "Setting up experiment $experimentName for the simulation." }
        executive.initializeCalendar()
        logger.info { "The executive was initialized prior to any experiments. Current time = $time" }
        executive.terminationWarningMsgOption = false
        markPreOrderTraversalModelElementHierarchy()
        // already should have reference to simulation
        advanceSubStreams(numberOfStreamAdvancesPriorToRunning)

        if (antitheticOption) {
            // make sure the streams are not reset after all replications are run
            setAllRVResetStartStreamOptions(false)
            // make sure that streams are not advanced to next sub-streams after each replication
            // antithetic option will control this (every other replication)
            setAllRVResetNextSubStreamOptions(false)
        } else {
            // tell the model to use the specifications from the experiment
            setAllRVResetStartStreamOptions(resetStartStreamOption)
            setAllRVResetNextSubStreamOptions(advanceNextSubStreamOption)
        }

        //TODO need to apply generic control types here someday

        if (hasExperimentalControls()) {
            val cMap: Map<String, Double>? = experimentalControls
            if (cMap != null) {
                // extract controls and apply them
                val k: Int = getControls().setControlsAsDoubles(cMap)
                logger.info("{} out of {} controls were applied to Model {} to setup the experiment.", k, cMap.size, name)
            }
        }

        // if the user has asked for the parameters, then they may have changed
        // thus apply the possibly new parameters to set up the model
//TODO  after converting RVParameterSetter to kotlin code
//        if (myRVParameterSetter != null) {
//            myRVParameterSetter.applyParameterChanges(this)
//        }

        // do all model element beforeExperiment() actions
        beforeExperimentActions()
    }

    internal fun runReplication() {
        if (maximumAllowedExecutionTimePerReplication > 0) {
            executive.maximumAllowedExecutionTime = maximumAllowedExecutionTimePerReplication
        }
        logger.info { "Initializing the executive" }
        executive.initialize()
        logger.info { "The executive was initialized prior to the replication. Current time = $time" }
        logger.info { "Setting up the replications for model elements" }
        setUpReplication()
        logger.info { "Executing the events" }
        executive.executeAllEvents()
        logger.info { "The executive finished executing events. Current time = $time" }
        logger.info { "Performing end of replication actions for model elements" }
        replicationEndedActions()
        logger.info { "Performing after replication actions for model elements" }
        afterReplicationActions()
    }

    internal fun endExperiment() {
        logger.info { "Performing after experiment actions for model elements" }
        afterExperimentActions()
    }


    private inner class ReplicationProcess(name: String?) : IterativeProcess<ReplicationProcess>(name) {

        override fun initializeIterations() {
            super.initializeIterations()
            myExperiment.resetCurrentReplicationNumber()
            setUpExperiment()
            if (repLengthWarningMessageOption) {
                if (lengthOfReplication.isInfinite()) {
                    if (maximumAllowedExecutionTimePerReplication == 0L) {
                        val sb = StringBuilder()
                        sb.append("Simulation: In initializeIterations(), preparing to run replications:")
                        sb.appendLine()
                        sb.append("The experiment has an infinite horizon.")
                        sb.appendLine()
                        sb.append("There was no maximum real-clock execution time specified.")
                        sb.appendLine()
                        sb.append("The user is responsible for ensuring that the replication is stopped.")
                        sb.appendLine()
                        logger.warn(sb.toString())
                        println(sb.toString())
                        System.out.flush()
                    }
                }
            }
        }

        override fun endIterations() {
            endExperiment()
            super.endIterations()
        }

        override fun hasNextStep(): Boolean {
            return hasMoreReplications()
        }

        override fun nextStep(): ReplicationProcess? {
            return if (!hasNextStep()) {
                null
            } else this
        }

        override fun runStep() {
            myCurrentStep = nextStep()
//            logger.info { "Simulation $name Running replication $currentReplicationNumber of $numberOfReplications replications" }
            myExperiment.incrementCurrentReplicationNumber()
            logger.info { "Running replication $currentReplicationNumber of $numberOfReplications replications" }
            model.runReplication()
            logger.info { "Ended replication $currentReplicationNumber of $numberOfReplications replications" }
            if (garbageCollectAfterReplicationFlag) {
                System.gc()
            }
        }

    }

    var simulationName: String = simulationName
        private set

    override val experimentId: Int
        get() = myExperiment.experimentId

    override var experimentName: String
        get() = myExperiment.experimentName
        set(value) {myExperiment.experimentName = value}

    override var numberOfReplications: Int
        get() = myExperiment.numberOfReplications
        set(value) {
            myExperiment.numberOfReplications = value
        }

    override fun numberOfReplications(numReps: Int, antitheticOption: Boolean) {
        myExperiment.numberOfReplications(numReps, antitheticOption)
    }

    override var lengthOfReplication: Double
        get() = myExperiment.lengthOfReplication
        set(value) {
            myExperiment.lengthOfReplication = value
        }

    override var lengthOfReplicationWarmUp: Double
        get() = myExperiment.lengthOfReplicationWarmUp
        set(value) {myExperiment.lengthOfReplicationWarmUp = value}

    override var replicationInitializationOption: Boolean
        get() = myExperiment.replicationInitializationOption
        set(value) {
            myExperiment.replicationInitializationOption = value
        }

    override var maximumAllowedExecutionTimePerReplication: Long
        get() = myExperiment.maximumAllowedExecutionTimePerReplication
        set(value) {
            myExperiment.maximumAllowedExecutionTimePerReplication = value
        }

    override var resetStartStreamOption: Boolean
        get() = myExperiment.resetStartStreamOption
        set(value) {
            myExperiment.resetStartStreamOption = value
        }

    override var advanceNextSubStreamOption: Boolean
        get() = myExperiment.advanceNextSubStreamOption
        set(value) {
            myExperiment.advanceNextSubStreamOption = value
        }

    override val antitheticOption: Boolean
        get() = myExperiment.antitheticOption

    override var numberOfStreamAdvancesPriorToRunning: Int
        get() = myExperiment.numberOfStreamAdvancesPriorToRunning
        set(value) {
            myExperiment.numberOfStreamAdvancesPriorToRunning = value
        }

    override var garbageCollectAfterReplicationFlag: Boolean
        get() = myExperiment.garbageCollectAfterReplicationFlag
        set(value) {
            myExperiment.garbageCollectAfterReplicationFlag = value
        }

    override var experimentalControls: Map<String, Double>?
        get() = myExperiment.experimentalControls
        set(value) {
            myExperiment.experimentalControls = value
        }

    override val currentReplicationNumber
        get() = myExperiment.currentReplicationNumber

    override fun hasExperimentalControls() = myExperiment.hasExperimentalControls()

    override fun hasMoreReplications() = myExperiment.hasMoreReplications()

    override fun setExperiment(e: Experiment) = myExperiment.setExperiment(e)

    /**
     * Returns true if additional replications need to be run
     *
     * @return true if additional replications need to be run
     */
    fun hasNextReplication(): Boolean {
        return myReplicationProcess.hasNextStep()
    }

    /**
     * Initializes the simulation in preparation for running
     */
    fun initializeReplications() {
        logger.info {"$name Initializing the replications ..."}
        myReplicationProcess.initialize()
    }

    /**
     * Runs the next replication if there is one
     */
    fun runNextReplication() {
        myReplicationProcess.runNext()
    }

    /** A convenience method for running a simulation
     *
     * @param expName the name of the experiment
     * @param numReps the number of replications
     * @param runLength the length of the simulation replication
     * @param warmUp the length of the warmup period
     */
    fun simulate(numReps: Int = 1, runLength: Double, warmUp: Double = 0.0, expName: String? = null) {
        if (expName != null) {
            experimentName = expName
        }
        numberOfReplications = numReps
        lengthOfReplication = runLength
        lengthOfWarmUp = warmUp
        simulate()
    }

    /**
     * Runs all remaining replications based on the current settings
     */
    fun simulate() {
        val startTime = Clock.System.now()
        logger.info {"Starting the simulation for $simulationName at time $startTime"}
        logger.info {"$name simulating all $numberOfReplications replications of length $lengthOfReplication with warm up $lengthOfReplicationWarmUp ..." }

//        simulationName = name + "_" + instantNow
        myReplicationProcess.run()
        val endTime = Clock.System.now()
        logger.info {"$name completed $numberOfReplications replications." }
        logger.info {"Ended the simulation for $simulationName at $endTime"}
        val elapsedTime = endTime - startTime
        logger.info {"Total time for $simulationName = $elapsedTime"}
    }

    /**
     * Causes the simulation to end after the current replication is completed
     *
     * @param msg A message to indicate why the simulation was stopped
     */
    fun endSimulation(msg: String? = null) {
        logger.info {"$name ending ... completed $numberReplicationsCompleted of $numberOfReplications" }
        myReplicationProcess.end(msg)
    }

    /**
     * Causes the simulation to stop the current replication and not complete any additional replications
     *
     * @param msg A message to indicate why the simulation was stopped
     */
    fun stopSimulation(msg: String?) {
        logger.info {"$name stopping ... with message $msg" }
        myReplicationProcess.stop(msg)
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Name: ")
        sb.append(simulationName)
        sb.appendLine()
        sb.append(myReplicationProcess)
        sb.appendLine()
        sb.append(myExperiment.toString())
        sb.appendLine()
        sb.append(myExecutive)
        return sb.toString()
    }

    companion object : KLoggable {
        /**
         * Used to assign unique enum constants
         */
        private var myEnumCounter_ = 0

        val nextEnumConstant: Int
            get() = ++myEnumCounter_

        /**
         *
         * @return a comparator that compares based on getId()
         */
        val modelElementComparator: Comparator<ModelElement>
            get() = ModelElementComparator()

        /**
         * A global logger for logging
         */
        override val logger = logger()
    }
}

