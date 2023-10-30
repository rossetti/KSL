package ksl.examples.general.models.timesharedcomputer

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.Variable
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc

class TimeSharedComputerPV(
    parent: ModelElement,
    numJobs: Int = 1000,
    numTerminals: Int = 20,
    thinkingTime: RVariableIfc = ExponentialRV(25.0, 1),
    serviceTime: RVariableIfc = ExponentialRV(0.8, 2),
    quantum: Double = 0.1,
    swapTime: Double = 0.015,
    name: String? = "TimeSharedComputerPV"
) : ProcessModel(parent, name) {

    private val myQuantum = quantum
    private val mySwapTime = swapTime
    private val myNumTerminals = numTerminals
    private val myNumJobs = numJobs

    private val myThinkingTimeRV = RandomVariable(this, thinkingTime)
    private val myServiceTimeRV = RandomVariable(this, serviceTime)

    private val myNumTerminalsThinking: TWResponse = TWResponse(this, "Number Thinking")
    private val myResponseTime: Response = Response(this, "Response Time")
    private val numJobsCompleted = Variable(this, 0.0)
    private val myNumJobsInProgress: TWResponse = TWResponse(this, "Number Jobs In Progress")

    private val cpu: ResourceWithQ = ResourceWithQ(this, "CPU")

    override fun initialize() {
        // start the terminals thinking
        for (i in 1..myNumTerminals) {
            val terminal = Terminal()
            activate(terminal.thinkingProcess)
        }
    }

    private inner class Terminal : Entity() {
        val thinkingProcess: KSLProcess = process() {
            do {
                myNumTerminalsThinking.increment()
                delay(myThinkingTimeRV)
                myNumTerminalsThinking.decrement()
                val job = ComputerJob()
                myNumJobsInProgress.increment()
                waitFor(job.computingProcess)
                myNumJobsInProgress.decrement()
                myResponseTime.value = time - job.startTime
                numJobsCompleted.value = numJobsCompleted.value + 1
            } while (numJobsCompleted.value <= myNumJobs)
        }
    }

    private inner class ComputerJob : Entity() {
        val startTime = time
        var remainingServiceTime = myServiceTimeRV.value
        val runtime: Double
            get() = if (myQuantum < remainingServiceTime) {
                myQuantum + mySwapTime
            } else {
                remainingServiceTime + mySwapTime
            }

        val computingProcess: KSLProcess = process() {
            while (remainingServiceTime > 0) {
                val a = seize(cpu)
                delay(runtime)
                release(a)
                remainingServiceTime = remainingServiceTime - myQuantum
            }
        }
    }
}

fun main() {
    val m = Model()
    TimeSharedComputerPV(m)
    m.numberOfReplications = 20
    m.simulate()
    m.print()
}