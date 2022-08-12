package ksl.simulation

import ksl.calendar.CalendarIfc
import ksl.calendar.PriorityQueueEventCalendar
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.io.KSL
import ksl.utilities.io.LogPrintWriter
import ksl.utilities.io.OutputDirectory
import mu.KLoggable
import java.nio.file.Path

private var simCounter: Int = 0

/**
 * Simulation represents a model and experiment that can be run. It encapsulates
 * a model to which model elements can be attached. It allows an experiment and
 * its run parameters to be specified. It allows reporting of results via a SimulationReporter.
 *
 * If you want to store simulation results in a database, then use the JSLDatabaseObserver
 * class to observe the simulation with an instance of the JSLDatabase class.
 *
 * @author Manuel Rossetti (rossetti@uark.edu)
 */
class Simulation(
    name: String = "Simulation_${++simCounter}",
    pathToOutputDirectory: Path = KSL.createSubDirectory(name + "_OutputDir"),
    calendar: CalendarIfc = PriorityQueueEventCalendar(),
) : IdentityIfc by Identity(name), Experiment(name + "_Experiment") {

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
     * @return the path to the simulation's output directory
     */
    val outputDirectoryPath: Path
        get() = outputDirectory.outDir

    /**
     *
     * @return the pre-defined default text output file for the simulation
     */
    val out: LogPrintWriter
        get() = outputDirectory.out

    /** A flag to control whether a warning is issued if the user does not
     * set the replication run length
     */
    var repLengthWarningMessageOption = true

    /**
     * Controls the execution of replications
     */
    private val myReplicationExecutionProcess: ReplicationExecutionProcess =
        ReplicationExecutionProcess("Simulation: Replication Execution Process")

    /**
     * A flag to indicate whether the simulation is done A simulation can be done if:
     * 1) it ran all of its replications 2) it was ended by a
     * client prior to completing all of its replications 3) it ended because it
     * exceeded its maximum allowable execution time before completing all of
     * its replications. 4) its end condition was satisfied
     *
     */
    val isDone: Boolean
        get() = myReplicationExecutionProcess.isDone

    /**
     * Returns if the elapsed execution time exceeds the maximum time allowed.
     * Only true if the maximum was set and elapsed time is greater than or
     * equal to maximumAllowedExecutionTime
     */
    val isExecutionTimeExceeded: Boolean
        get() = myReplicationExecutionProcess.isExecutionTimeExceeded

    /**
     * Returns system time in nanoseconds that the simulation started
     */
    val beginExecutionTime: Long
        get() = myReplicationExecutionProcess.beginExecutionTime

    /**
     * Gets the clock time in nanoseconds since the simulation was
     * initialized
     */
    val elapsedExecutionTime: Long
        get() = myReplicationExecutionProcess.elapsedExecutionTime

    /**
     * Returns system time in nanoseconds that the simulation ended
     */
    val endExecutionTime: Long
        get() = myReplicationExecutionProcess.endExecutionTime

    /**
     * The maximum allotted (suggested) execution (real) clock for the
     * entire iterative process in nanoseconds. This is a suggested time because the execution
     * time requirement is only checked after the completion of an individual
     * step After it is discovered that cumulative time for executing the step
     * has exceeded the maximum time, then the iterative process will be ended
     * (perhaps) not completing other steps.
     */
    var maximumAllowedExecutionTime: Long
        get() = myReplicationExecutionProcess.maximumAllowedExecutionTime
        set(value) {
            myReplicationExecutionProcess.maximumAllowedExecutionTime = value
        }

    /**
     * Returns the replications completed since the simulation was
     * last initialized
     *
     * @return the number of replications completed
     */
    val numberReplicationsCompleted: Int
        get() = myReplicationExecutionProcess.numberStepsCompleted

    /**
     * Checks if the simulation is in the created state. If the
     * simulation is in the created execution state this method will return true
     *
     * @return true if in the created state
     */
    val isCreated: Boolean
        get() = myReplicationExecutionProcess.isCreated

    /**
     * Checks if the simulation is in the initialized state After the
     * simulation has been initialized this method will return true
     *
     * @return true if initialized
     */
    val isInitialized: Boolean
        get() = myReplicationExecutionProcess.isInitialized

    /**
     * A simulation is running if it has been told to run (i.e.
     * run() or runNextReplication()) but has not yet been told to end().
     *
     */
    val isRunning: Boolean
        get() = myReplicationExecutionProcess.isRunning

    /**
     * Checks if the simulation is in the completed step state After the
     * simulation has successfully completed a replication this property will be true
     */
    val isReplicationCompleted: Boolean
        get() = myReplicationExecutionProcess.isStepCompleted

    /**
     * Checks if the simulation is in the ended state. After the simulation has been ended this property will return true
     */
    val isEnded: Boolean
        get() = myReplicationExecutionProcess.isEnded

    /**
     * The simulation may end by a variety of means, this  checks
     * if the simulation ended because it ran all of its replications, true if all completed
     */
    val allReplicationsCompleted: Boolean
        get() = myReplicationExecutionProcess.allStepsCompleted

    /**
     * The simulation may end by a variety of means, this method checks
     * if the simulation ended because it was stopped, true if it was stopped via stop()
     */
    val stoppedByCondition: Boolean
        get() = myReplicationExecutionProcess.stoppedByCondition

    /**
     * The simulation may end by a variety of means, this method checks
     * if the simulation ended but was unfinished, not all replications were completed.
     */
    val isUnfinished: Boolean
        get() = myReplicationExecutionProcess.isUnfinished


    val model: Model = Model(this, calendar)

    /**
     *  Provides a clone/instance of the experiment information for the simulation.
     *  This is useful to set up other experimental parameters without changing
     *  this simulation's experimental settings.
     */
    val experiment: Experiment
        get() = instance()

    private inner class ReplicationExecutionProcess(name: String?) : IterativeProcess<Simulation>(name) {
        //TODO not sure if Simulation should be the type parameter

        override fun initializeIterations() {
            super.initializeIterations()
            resetCurrentReplicationNumber()
            model.setUpExperiment()
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
                        sb.append("The user is responsible for ensuring that the simulation is stopped.")
                        sb.appendLine()
                        logger.warn(sb.toString())
                        println(sb.toString())
                        System.out.flush()
                    }
                }
            }
        }

        override fun endIterations() {
            model.endExperiment()
            super.endIterations()
        }

        override fun hasNextStep(): Boolean {
            return hasMoreReplications()
        }

        override fun nextStep(): Simulation? {
            return if (!hasNextStep()) {
                null
            } else this@Simulation
        }

        override fun runStep() {
            myCurrentStep = nextStep()
            incrementCurrentReplicationNumber()
            logger.info {"Simulation: $name Running replication $currentReplicationNumber of $numberOfReplications replications"}
            model.runReplication()
            logger.info {"Simulation: $name Ended replication $currentReplicationNumber of $numberOfReplications replications"}
            if (garbageCollectAfterReplicationFlag) {
                System.gc()
            }
        }

    }

    /**
     * Returns true if additional replications need to be run
     *
     * @return true if additional replications need to be run
     */
    fun hasNextReplication(): Boolean {
        return myReplicationExecutionProcess.hasNextStep()
    }

    /**
     * Initializes the simulation in preparation for running
     */
    fun initialize() {
        logger.info {"Simulation: $name Initializing ..."}
        myReplicationExecutionProcess.initialize()
    }

    /**
     * Runs the next replication if there is one
     */
    fun runNextReplication() {
        myReplicationExecutionProcess.runNext()
    }

    /** A convenience method for running a simulation
     *
     * @param expName the name of the experiment
     * @param numReps the number of replications
     * @param runLength the length of the simulation replication
     * @param warmUp the length of the warmup period
     */
    fun run(numReps: Int = 1, runLength: Double, warmUp: Double = 0.0, expName: String? = null) {
        if (expName != null) {
            experimentName = expName
        }
        numberOfReplications = numReps
        lengthOfReplication = runLength
        lengthOfWarmUp = warmUp
        run()
    }

    /**
     * Runs all remaining replications based on the current settings
     */
    fun run() {
        logger.info {"Simulation: $name Running all $numberOfReplications replications of length $lengthOfReplication with warm up $lengthOfWarmUp ..." }
        myReplicationExecutionProcess.run()
        logger.info {"Simulation: $name completed $numberOfReplications replications." }

    }

    /**
     * Causes the simulation to end after the current replication is completed
     *
     * @param msg A message to indicate why the simulation was stopped
     */
    fun end(msg: String? = null) {
        logger.info {"Simulation: $name ending ... completed $numberReplicationsCompleted of $numberOfReplications" }
        myReplicationExecutionProcess.end(msg)
    }

    /**
     * Causes the simulation to stop the current replication and not complete any additional replications
     *
     * @param msg A message to indicate why the simulation was stopped
     */
    fun stop(msg: String?) {
        logger.info {"Simulation: $name stopping ... with message $msg" }
        myReplicationExecutionProcess.stop(msg)
    }

    companion object : KLoggable {
        /**
         * A global logger for logging
         */
        override val logger = logger()
    }
}