package ksl.modeling.fleet

import ksl.modeling.agv.AgvVehicle

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.entity.HoldQueue
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  V3 of the AGV plan's Phase 0. The lifecycle of a *runtime* agent -- one created inside
 *  `initialize()` rather than at setup time -- over several replications.
 *
 *  A `KSLProcess` is a coroutine that runs once, and `AgentModel.initialize()` restarts registered
 *  agents' statecharts but does **not** re-activate their processes. So a vehicle agent must be
 *  created fresh each replication, which means it is created while `model.isRunning` is true, which
 *  means `AgentModel` does not put it in the `agents` registry. Three consequences are asserted
 *  here because each is the kind of thing a later contributor would "fix" in the wrong direction.
 *
 *  1. **The fleet must be enumerated from the subsystem, never from `AgentModel.agents`.** The
 *     registry is empty of these agents by design, not by accident.
 *  2. **A fresh agent gets a fresh mailbox, and the reason is that it is a fresh object.** A bid
 *     left over from a previous replication would be a silent cross-replication leak. The test
 *     shows the mechanism rather than assuming it: the *previous* replication's agent is observed
 *     still holding its two unconsumed messages at the start of the next replication -- nothing
 *     reset it, because `AgentModel.initialize()` resets mailboxes only for agents in the `agents`
 *     registry, and a runtime agent is deliberately not in it -- while the *new* agent starts
 *     empty. Freshness comes from newness alone. That is sound here only because the dead agent is
 *     unreachable garbage; a design that kept a handle on last replication's agent would read
 *     stale traffic, which is why the fleet is enumerated from the permanent `AgvVehicle` objects
 *     and the agent handle is replaced, never reused.
 *  3. **An unbounded `while (true)` control loop is terminated at each replication boundary, with
 *     nothing left behind.** No shutdown flag, no cooperation from the loop: the termination is
 *     entirely inherited from `ProcessModel.afterReplication`, and the queues are cleared by
 *     `Queue.afterReplication`. Because there is no subsystem code doing this, there is nothing a
 *     maintainer could read to discover that the property holds -- hence the test.
 *
 *  This is the shape §11.3 of the OOD specifies for `VehicleAgent`, reduced to the lifecycle facts.
 */
class RuntimeAgentLifecycleTest {

    private class Bell(override val from: ksl.modeling.entity.ProcessModel.Entity) :
        AgentMessage.Inform<String>(from, "wake up")

    private class Depot(parent: ModelElement) : AgentModel(parent, "Depot") {

        /** Where a worker with nothing to do is dormant. Stands in for `availabilityQ`. */
        val idleQ = HoldQueue(this, "Depot:IdleQ")

        val observations = mutableListOf<String>()

        /** The live agent for this replication, if any. Stands in for `AgvVehicle.agent`. */
        var worker: Worker? = null
            private set

        /** The previous replication's agent, kept only so the test can observe that nothing reset
         *  it. Production code must not hold this -- see the class comment. */
        private var priorWorker: Worker? = null

        /** An unbounded control loop, exactly as the design specifies. It never returns on its own. */
        inner class Worker(name: String) : Agent(name) {
            var loops = 0
                private set

            val control = process(isDefaultProcess = true) {
                while (true) {
                    hold(idleQ, suspensionName = "dormant")
                    loops++
                }
            }
        }

        override fun initialize() {
            // Snapshot what the previous replication left behind, BEFORE creating this one's agent.
            priorWorker = worker
            worker = Worker("Worker").also { activate(it.control) }
            observations.add(
                "rep=${model.currentReplicationNumber} INIT idleQ=${idleQ.size} " +
                        "registry=${agents.size} " +
                        "priorMailbox=${priorWorker?.mailbox?.size ?: -1} " +
                        "priorLoops=${priorWorker?.loops ?: -1} " +
                        "freshMailbox=${worker!!.mailbox.size} " +
                        "freshLoops=${worker!!.loops} " +
                        "isNewObject=${priorWorker !== worker}"
            )
            // Ring the bell twice, then leave the worker dormant when the horizon falls.
            schedule(::ring, 5.0)
            schedule(::ring, 10.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun ring(event: KSLEvent<Nothing>) {
            // A message the agent never consumes: if any of it survived into the next replication,
            // the "fresh mailbox" claim would be false.
            worker?.mailbox?.deliver(Bell(worker!!))
            idleQ.removeAllAndResume()
        }

        override fun replicationEnded() {
            observations.add(
                "rep=${model.currentReplicationNumber} ENDED idleQ=${idleQ.size} " +
                        "registry=${agents.size} " +
                        "mailbox=${worker?.mailbox?.size ?: -1} loops=${worker?.loops ?: -1}"
            )
        }
    }

    @Test
    @DisplayName("V3: a runtime agent is fresh each replication, absent from the registry, and terminated cleanly")
    fun runtimeAgentLifecycle() {
        val m = Model("RuntimeAgent")
        val depot = Depot(m)
        m.numberOfReplications = 3
        m.lengthOfReplication = 20.0
        m.simulate()

        val obs = depot.observations
        assertEquals(6, obs.size, "expected an INIT and an ENDED line per replication: $obs")

        // Replication 1 starts from nothing.
        assertEquals(
            "rep=1 INIT idleQ=0 registry=0 priorMailbox=-1 priorLoops=-1 " +
                    "freshMailbox=0 freshLoops=0 isNewObject=true",
            obs[0], obs.toString()
        )

        // Each replication does the same work: two rings, so two loop passes, and the horizon falls
        // with the worker dormant in the queue and one unconsumed message in its mailbox.
        for (rep in 1..3) {
            assertEquals(
                "rep=$rep ENDED idleQ=1 registry=0 mailbox=2 loops=2",
                obs[2 * rep - 1],
                "replication $rep did not end in the hazardous state the test needs: $obs"
            )
        }

        // 1. The agents are never in the registry, so the fleet cannot be enumerated from it.
        assertTrue(obs.all { it.contains("registry=0") },
            "a runtime agent reached AgentModel.agents; the plan's enumeration rule (finding 1.3) " +
                    "no longer holds: $obs")

        // 2 and 3. Every later replication begins clean, and the observation says exactly why.
        //
        // `idleQ=0` -- the previous worker's unbounded `while (true)` loop was terminated while it
        // was dormant in the queue, and the queue was cleared. Neither is subsystem code: both are
        // inherited from ProcessModel.afterReplication and Queue.afterReplication. No shutdown flag
        // was needed and the loop cooperated in no way.
        //
        // `priorMailbox=2` -- the DEAD agent still holds both messages it never consumed. Nothing
        // reset it, because mailbox reset runs only for agents in the registry and a runtime agent
        // is deliberately not in it. This is the finding that makes the next clause load-bearing
        // rather than incidental.
        //
        // `freshMailbox=0, isNewObject=true` -- the live agent is a different object and starts
        // empty. Freshness is a consequence of construction, not of any reset. A design that reused
        // last replication's agent would therefore read stale traffic.
        for (rep in 2..3) {
            assertEquals(
                "rep=$rep INIT idleQ=0 registry=0 priorMailbox=2 priorLoops=2 " +
                        "freshMailbox=0 freshLoops=0 isNewObject=true",
                obs[2 * rep - 2],
                "replication $rep did not start clean: $obs"
            )
        }
    }
}
