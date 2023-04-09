package ksl.examples.general.models.timesharedcomputer

import ksl.modeling.queue.Queue
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc

class TimeSharedComputerEV2(
    parent: ModelElement,
    numJobs: Int = 1000,
    numTerminals: Int = 80,
    thinkingTime: RVariableIfc = ExponentialRV(25.0, 1),
    serviceTime: RVariableIfc = ExponentialRV(0.8, 2),
    quantum: Double = 0.1,
    swapTime: Double = 0.015,
    name: String? = "TimeSharedComputerEV"
) : ModelElement(parent, name) {
    init{
        require(numJobs > 0){"The number of jobs must be > 0"}
        require(numTerminals > 0){"The number of terminals must be > 0"}
        require(quantum > 0){"The quantum must be > 0"}
        require(swapTime > 0){"The swap time must be > 0"}
    }
    private val myQuantum = quantum
    private val mySwapTime = swapTime
    private val myNumTerminals = numTerminals

    private val myThinkingTimeRV = RandomVariable(this, thinkingTime)
    private val myServiceTimeRV = RandomVariable(this, serviceTime)
    private val myCPUJobQ = Queue<ComputerJob>(this, "CPU Job Q")
    private val myCPU = TWResponse(this, "CPU")

    private val myNumTerminalsThinking: TWResponse = TWResponse(this, "Number Thinking")
    private val myResponseTime: Response = Response(this, "Response Time")
    private val myNumJobsInProgress: TWResponse = TWResponse(this, "Number Jobs In Progress")

    init {
        myResponseTime.addCountLimitStoppingAction(numJobs)
    }

    private inner class ComputerJob() : QObject() {
        var myArrivalTime = time
        var myRemainingServiceTime = myServiceTimeRV.value
        val runtime: Double
            get() = if (myQuantum < myRemainingServiceTime) {
                myQuantum + mySwapTime
            } else {
                myRemainingServiceTime + mySwapTime
            }
    }

    override fun initialize() {
        // start the terminals thinking
        for (i in 1..myNumTerminals) {
            //val job = ComputerJob()
            schedule(this::endOfThinkTime, myThinkingTimeRV)
            // increment the number thinking
            myNumTerminalsThinking.increment()
        }
    }

    private fun endOfThinkTime(event: KSLEvent<Nothing>){
        // no longer thinking, decrement the number thinking
        myNumTerminalsThinking.decrement()
        // create the new job
        val newJob = ComputerJob()
        myNumJobsInProgress.increment()
        // enqueue the job for the cpu
        myCPUJobQ.enqueue(newJob)
        // check if cpu is idle, if so serve the next job
        if (myCPU.value == 0.0) { // no job using CPU, thus idle
            // get the next job from the cpu waiting Q
            processNextJob()
        }
    }

    private fun processNextJob(){
        val job = myCPUJobQ.removeNext()!!
        myCPU.increment()
        // schedule the job to run
        schedule(this::endService, job.runtime, job)
        // adjust the remaining service, subtract quantum (don't include swap time)
        // if less than zero, this indicates no service left
        job.myRemainingServiceTime = job.myRemainingServiceTime - myQuantum
    }

    private fun endService(event: KSLEvent<ComputerJob>) {
        val currentJob = event.message!!
        myCPU.decrement()
        if (currentJob.myRemainingServiceTime > 0.0) { // job requires more service
            // place job in cpu's waiting queue, for round-robin
            myCPUJobQ.enqueue(currentJob)
            // just finished a job so must be idle and should start next job
            processNextJob()
        } else {
            completedJob(currentJob)
            // if more jobs waiting for cpu, then serve them
            if (myCPUJobQ.isNotEmpty) {
                processNextJob()
            }
        }
    }

    private fun completedJob(completedJob: ComputerJob){
        myNumJobsInProgress.decrement()
        // job done, get the response time
        val ws: Double = time - completedJob.myArrivalTime
        // record the response time using the response variable
        myResponseTime.value = ws
        // schedule the end of the thinking time
        schedule(this::endOfThinkTime, myThinkingTimeRV)
        // increment the number thinking
        myNumTerminalsThinking.increment()
    }

}

fun main() {
    val m = Model()
    TimeSharedComputerEV2(m)
    m.numberOfReplications = 2000
    m.simulate()
    m.print()
}