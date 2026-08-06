/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.agent

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.contractNet
import ksl.modeling.agent.positive
import ksl.modeling.agent.receiveMessage
import ksl.modeling.agent.receiveMessageOfType
import ksl.modeling.agent.sendMessage
import ksl.modeling.entity.KSLProcess
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  A task-oriented agentic workflow example: a dispatcher allocates
 *  arriving jobs to one of several worker agents using Contract-Net.
 *
 *  This is the canonical "agents-as-task-coordinators" use case from the
 *  design doc — no spatial component, no agent movement, just typed
 *  message-based negotiation. It exercises every piece of Phase 1a–1c:
 *   - `AgentModel` / `Agent` (Phase 1a)
 *   - `AgentMailbox` and FIPA-style `AgentMessage` performatives (1a)
 *   - `process { }` agent behavior (existing ProcessModel)
 *   - `contractNet` Contract-Net helper (Phase 1c)
 *
 *  The model:
 *   - `Dispatcher` generates new jobs at exponential inter-arrival times.
 *     For each job it runs a Contract-Net round against the workers,
 *     selecting whichever bidder quotes the lowest estimated completion
 *     time.
 *   - Each `Worker` listens for incoming CFP requests. On receipt it
 *     estimates how long the job will take given its own service rate
 *     and current backlog, replies with a `Propose`, then waits for an
 *     `Accept` or `Reject`. On `Accept` it adds the job to its backlog
 *     and processes it; on `Reject` it ignores and returns to listening.
 */
class JobShopExample(parent: ModelElement, name: String? = null) : AgentModel(parent, name) {

    /**
     *  A job carries its id, base service-time demand, and the
     *  simulation time it entered the system, so the worker that
     *  ends up processing it can record time-in-system on completion.
     */
    data class Job(val id: Int, val workNeeded: Double, val createdAt: Double)

    /** A bid: how long this worker estimates the job will take to finish. */
    data class Bid(val estimatedCompletionTime: Double)

    // --- Modeling responses --------------------------------------------------

    private val tisResponse: Response = Response(this, "JobTimeInSystem")
    private val numInSystem: TWResponse = TWResponse(this, "NumJobsInSystem")
    private val workerUtilization: List<TWResponse> = (1..3).map {
        TWResponse(this, "Worker$it:Busy")
    }

    // --- Job arrival and routing parameters (initialized from Defaults) -----

    /** Time between successive job arrivals. */
    var jobArrivalRV: RandomIfc =
        ExponentialRV(Defaults.jobArrivalMean, streamNum = 1, streamProvider = streamProvider)

    /** Per-job base service time required. */
    var jobWorkRV: RandomIfc =
        ExponentialRV(Defaults.jobWorkMean, streamNum = 2, streamProvider = streamProvider)

    /** How long the dispatcher waits for proposals on each CFP round. */
    var bidDeadline: Double by positive(Defaults.bidDeadline)

    /** Mutable global defaults for [JobShopExample]. */
    companion object Defaults {
        /** Mean inter-arrival time between jobs. Must be positive. */
        var jobArrivalMean: Double by positive(8.0)
        /** Mean job service-time demand. Must be positive. */
        var jobWorkMean: Double by positive(5.0)
        /** Dispatcher CFP deadline. Must be positive. */
        var bidDeadline: Double by positive(0.5)
    }

    // --- Workers and dispatcher ---------------------------------------------

    inner class Worker(aName: String, val serviceRate: Double, private val utilization: TWResponse) :
        Agent(aName) {

        private var backlog: Int = 0

        val behavior: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                // Wait for a CFP.
                val cfp = receiveMessageOfType<AgentMessage.Request<Job>, AgentMessage>(mailbox)

                // Bid: estimate total queue time + service time at our rate.
                val estimated = (backlog + 1) * cfp.payload.workNeeded / serviceRate
                sendMessage(
                    AgentMessage.Propose(
                        from = this@Worker,
                        proposal = Bid(estimated),
                        conversationId = cfp.conversationId!!,
                    ),
                    (cfp.from as AgentModel.Agent).mailbox,
                )

                // Wait for the dispatcher's decision on this conversation.
                val decision = receiveMessage(mailbox) { msg ->
                    msg.conversationId == cfp.conversationId &&
                        (msg is AgentMessage.Accept || msg is AgentMessage.Reject)
                }

                if (decision is AgentMessage.Accept) {
                    // Accepted — do the work.
                    backlog += 1
                    utilization.value = 1.0
                    delay(cfp.payload.workNeeded / serviceRate)
                    backlog -= 1
                    if (backlog == 0) utilization.value = 0.0
                    numInSystem.decrement()
                    tisResponse.value = currentTime - cfp.payload.createdAt
                }
                // On Reject (or any other), loop and wait for the next CFP.
            }
        }
    }

    inner class Dispatcher : Agent("dispatcher") {

        /** Number of jobs successfully assigned to a worker. */
        var jobsAssigned: Int = 0
            private set

        /** Number of jobs that ran a CFP but got zero proposals back. */
        var jobsUnassigned: Int = 0
            private set

        val behavior: KSLProcess = process(isDefaultProcess = true) {
            var jobId = 0
            while (true) {
                delay(jobArrivalRV.value)
                jobId += 1
                val job = Job(jobId, jobWorkRV.value, createdAt = currentTime)
                numInSystem.increment()

                val outcome = contractNet<Job, Bid>(
                    bidders = workers,
                    callForProposals = job,
                    deadline = bidDeadline,
                    selectBest = { proposals ->
                        proposals.minByOrNull { it.proposal.estimatedCompletionTime }
                    },
                )

                if (outcome != null) jobsAssigned += 1 else { jobsUnassigned += 1; numInSystem.decrement() }
            }
        }
    }

    val workers: List<Worker> = listOf(
        Worker("worker1", serviceRate = 1.0, utilization = workerUtilization[0]),
        Worker("worker2", serviceRate = 1.5, utilization = workerUtilization[1]),
        Worker("worker3", serviceRate = 0.8, utilization = workerUtilization[2]),
    )

    val dispatcher: Dispatcher = Dispatcher()

    override fun initialize() {
        super.initialize()
        for (w in workers) activate(w.behavior)
        activate(dispatcher.behavior)
    }
}

fun main() {
    val model = Model("JobShopExample")
    val sys = JobShopExample(model, "jobshop")
    model.lengthOfReplication = 1000.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
    println("Jobs assigned: ${sys.dispatcher.jobsAssigned}")
    println("Jobs unassigned: ${sys.dispatcher.jobsUnassigned}")
}
