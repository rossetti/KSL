/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general.models.timesharedcomputer

import ksl.modeling.queue.Queue
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc

class TimeSharedComputerEV(
    parent: ModelElement,
    numJobs: Int = 1000,
    numTerminals: Int = 80,
    thinkingTime: RVariableIfc = ExponentialRV(25.0, 1),
    serviceTime: RVariableIfc = ExponentialRV(0.8, 2),
    quantum: Double = 0.1,
    swapTime: Double = 0.015,
    name: String? = "TimeSharedComputerEV"
) : ModelElement(parent, name) {

    private val myThinkingTimeRV = RandomVariable(this, thinkingTime)
    private val myServiceTimeRV = RandomVariable(this, serviceTime)
    private val myCPUJobQ = Queue<ComputerJob>(this, "CPU Job Q")
    private val myUsingCPUQ = Queue<ComputerJob>(this, "Using CPU Q")

    private val myNumTerminalsThinking: TWResponse = TWResponse(this, "Number Thinking", numTerminals.toDouble())
    private val myResponseTime: Response = Response(this, "Response Time")

    init {
        myResponseTime.addCountLimitStoppingAction(numJobs)
    }

    private val myQuantum = quantum
    private val mySwapTime = swapTime
    private val myNumTerminals = numTerminals
    private var count = 0

    private inner class ComputerJob() : QObject() {
        var myArrivalTime = 0.0
        var myRemainingServiceTime = 0.0
    }

    override fun initialize() {
        count = 0
        // start the terminals thinking
        for (i in 1..myNumTerminals) {
            val job = ComputerJob()
            schedule(this::jobArrival, myThinkingTimeRV, job)
        }
    }

    private fun jobArrival(event: KSLEvent<ComputerJob>) {
        // get the job from the event's message
        val job: ComputerJob = event.message!!
        // set the job's arrival time
        job.myArrivalTime = time
        // set the job's service time
        job.myRemainingServiceTime = myServiceTimeRV.value
        // no longer thinking, decrement the number thinking
        myNumTerminalsThinking.decrement()
        // enqueue the job for the cpu
        myCPUJobQ.enqueue(job)
        // check if cpu is idle, if so serve the next job
        if (myUsingCPUQ.isEmpty) { // no job using CPU, thus idle
            serveNextJob()
        }
    }

    private fun endService(event: KSLEvent<Nothing>) {
        // remove the job from the using cpu queue
        val job = myUsingCPUQ.removeNext()!!
        if (job.myRemainingServiceTime > 0.0) { // job requires more service
            // place job in cpu's waiting queue
            myCPUJobQ.enqueue(job)
            // just finished a job so must be idle and should start next job
            serveNextJob()
        } else {
            // job done, get the response time
            val ws: Double = time - job.myArrivalTime
            // record the response time using the response variable
            myResponseTime.value = ws
            // increment the number thinking
            myNumTerminalsThinking.increment()
            // schedule the end of the thinking time
            schedule(this::jobArrival, myThinkingTimeRV, job)
            // if more jobs waiting for cpu, then serve them
            if (myCPUJobQ.size > 0) {
                serveNextJob()
            }
        }
    }

    private fun serveNextJob() {
        // get the next job from the cpu waiting Q
        val job = myCPUJobQ.removeNext()!!
        // determine the cpu run time for this pass, including the swap time
        val runtime = if (myQuantum < job.myRemainingServiceTime) {
            myQuantum + mySwapTime
        } else {
            job.myRemainingServiceTime + mySwapTime
        }
        // adjust the remaining service, subtract quantum (don't include swap time)
        // if less than zero, this indicates no service left
        job.myRemainingServiceTime = job.myRemainingServiceTime - myQuantum
        // place the job into the cpu
        myUsingCPUQ.enqueue(job)
        // schedule the job to run
        schedule(this::endService, runtime)
    }

}

fun main() {
    val m = Model()
    TimeSharedComputerEV(m)
    m.numberOfReplications = 200
    m.simulate()
    m.print()
}