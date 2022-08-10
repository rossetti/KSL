package ksl.simulation

import jsl.simulation.IterativeProcess
import ksl.utilities.io.OutputDirectory
import ksl.calendar.CalendarIfc
import ksl.calendar.PriorityQueueEventCalendar
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.io.KSL
import ksl.utilities.io.LogPrintWriter
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

    /**
     *
     * @return the defined OutputDirectory for the simulation
     */
    val outputDirectory: OutputDirectory = OutputDirectory(pathToOutputDirectory, "kslOutput.txt")

    val model: Model = Model(this, calendar)

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

//    val executive: Executive = model.myExecutive //TODO not sure if needed

    /** A flag to control whether a warning is issued if the user does not
     * set the replication run length
     */
    var repLengthWarningMessageOption = true

    val isRunning: Boolean
        get() = TODO(" not implemented yet")

//    val experiment: Experiment = Experiment(name + "_Experiment")

    private inner class ReplicationExecutionProcess : IterativeProcess<Simulation>() {

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

        override fun hasNext(): Boolean {
            return hasMoreReplications()
        }

        override fun next(): Simulation? {
            return if (!hasNext()) {
                null
            } else this@Simulation
        }

        override fun runStep() {
            myCurrentStep = next()
            incrementCurrentReplicationNumber()
            model.runReplication()
            if (garbageCollectAfterReplicationFlag) {
                System.gc()
            }
        }

    }

    companion object : KLoggable {
        /**
         * A global logger for logging
         */
        override val logger = logger()
    }
}