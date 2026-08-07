/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.agent

import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Phase C2 — `AgentModel.AgentGenerator`.
 *
 *  The construct had **no instantiation anywhere in the repository** — no example,
 *  no test, no fixture — despite being the agent layer's mirror of
 *  `EntityGenerator` and the most natural entry point for a reader arriving from
 *  the process view. A construct that has never run is a construct that might not
 *  work.
 *
 *  Deterministic inter-arrival times are used throughout so counts are exact rather
 *  than distributional: the point is to pin the generator's contract, not to
 *  re-test the random-variate machinery.
 */
class AgentGeneratorTest {

    /**
     *  Counts agents created and processes actually run, optionally joining each new
     *  agent to a context, and optionally omitting the default process so the
     *  failure path can be exercised.
     */
    private class GenModel(
        parent: ModelElement,
        val useContext: Boolean = false,
        val withDefaultProcess: Boolean = true,
        val maxAgents: Long = Long.MAX_VALUE,
        val timeOfLast: Double = Double.POSITIVE_INFINITY,
    ) : AgentModel(parent, "genModel") {

        val ctx: Context<Worker> = Context("workers")
        var created: Int = 0
        val ran = mutableListOf<Double>()

        inner class Worker(aName: String) : Agent(aName) {
            init {
                if (withDefaultProcess) {
                    process(isDefaultProcess = true) {
                        ran.add(currentTime)
                        delay(0.5)
                    }
                }
            }
        }

        @Suppress("unused")
        private val generator = AgentGenerator(
            agentFactory = { Worker("w-${++created}") },
            timeUntilFirst = ConstantRV(1.0),
            timeBetween = ConstantRV(1.0),
            context = if (useContext) ctx else null,
            maxAgents = maxAgents,
            timeOfLast = timeOfLast,
        )
    }

    private fun run(m: GenModel, length: Double = 10.5, reps: Int = 1): GenModel {
        m.model.numberOfReplications = reps
        m.model.lengthOfReplication = length
        m.model.simulate()
        return m
    }

    // ── Generation and activation ────────────────────────────────────────────

    @Test
    @DisplayName("C2: generates on schedule and activates each agent's default process")
    fun generatesOnScheduleAndActivates() {
        val m = run(GenModel(Model("basic")))
        // First at t = 1, then every 1.0 through t = 10 within a 10.5 run.
        assertEquals(10, m.created, "one agent per unit time")
        assertEquals(
            (1..10).map { it.toDouble() }, m.ran,
            "each agent's default process should run at its creation time",
        )
    }

    @Test
    @DisplayName("C2: maxAgents caps the number created")
    fun maxAgentsIsHonoured() {
        val m = run(GenModel(Model("capped"), maxAgents = 3))
        assertEquals(3, m.created)
        assertEquals(listOf(1.0, 2.0, 3.0), m.ran)
    }

    @Test
    @DisplayName("C2: timeOfLast stops generation")
    fun timeOfLastIsHonoured() {
        val m = run(GenModel(Model("untilTime"), timeOfLast = 4.0))
        assertTrue(m.created in 3..4, "generation should stop around t = 4; got ${m.created}")
        assertTrue(m.ran.all { it <= 4.0 }, "nothing should run after timeOfLast; got ${m.ran}")
    }

    // ── The placement contract (A-5 / gate D3) ───────────────────────────────

    /**
     *  Without a `context`, the generator creates and activates but the agent joins
     *  nothing. That is the documented contract — placement is a modelling decision —
     *  and the point of pinning it is that the failure is otherwise silent: such an
     *  agent has no projection position and is invisible to `Population` queries.
     */
    @Test
    @DisplayName("C2: with no context, generated agents join nothing")
    fun withoutContextAgentsJoinNothing() {
        val m = run(GenModel(Model("noContext"), useContext = false))
        assertTrue(m.created > 0, "agents were generated")
        assertEquals(0, m.ctx.size, "no context was supplied, so membership is the factory's job")
    }

    /** With a `context`, every generated agent is a member. */
    @Test
    @DisplayName("C2: with a context, every generated agent joins it")
    fun withContextAgentsJoinIt() {
        val m = run(GenModel(Model("withContext"), useContext = true))
        assertEquals(m.created, m.ctx.size, "every generated agent should be a member")
    }

    /**
     *  Membership must be established *before* activation, so an agent's own process
     *  sees itself as a member from its first instruction. Ordering the other way
     *  round would be a subtle trap for any process that queries its neighbourhood
     *  immediately.
     */
    @Test
    @DisplayName("C2: an agent is in its context before its process starts")
    fun agentIsInContextBeforeItsProcessRuns() {
        val model = Model("orderCheck")
        val m = object : AgentModel(model, "order") {
            val ctx: Context<Worker> = Context("workers")
            val membershipAtStart = mutableListOf<Boolean>()
            var created = 0

            inner class Worker(aName: String) : Agent(aName) {
                init {
                    process(isDefaultProcess = true) {
                        membershipAtStart.add(this@Worker in ctx.members)
                        delay(0.5)
                    }
                }
            }

            @Suppress("unused")
            private val generator = AgentGenerator(
                agentFactory = { Worker("w-${++created}") },
                timeUntilFirst = ConstantRV(1.0),
                timeBetween = ConstantRV(1.0),
                context = ctx,
                maxAgents = 3,
            )
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 6.0
        model.simulate()
        assertEquals(listOf(true, true, true), m.membershipAtStart)
    }

    // ── Failure path ─────────────────────────────────────────────────────────

    /**
     *  An agent with no default process cannot be activated. The generator must say
     *  so clearly, naming the agent and the remedy, rather than failing obscurely
     *  inside the process machinery.
     */
    @Test
    @DisplayName("C2: an agent with no default process fails with a message that names the fix")
    fun missingDefaultProcessIsReportedClearly() {
        val model = Model("noProcess")
        GenModel(model, withDefaultProcess = false)
        model.numberOfReplications = 1
        model.lengthOfReplication = 5.0
        val message = try {
            model.simulate(); null
        } catch (e: Exception) {
            generateSequence(e as Throwable) { it.cause }
                .mapNotNull { it.message }
                .firstOrNull { "default process" in it }
        }
        assertTrue(
            message != null && "process(isDefaultProcess = true)" in message,
            "the failure should name the remedy; got: $message",
        )
    }

    // ── Replications ─────────────────────────────────────────────────────────

    /**
     *  A generator held as a model field is the *correct* shape, precisely because it
     *  manufactures a new agent — and so a new one-shot `KSLProcess` — on every
     *  firing. This is the counter-example to the trap that broke five shipped
     *  examples, so it is worth asserting rather than assuming.
     */
    @Test
    @DisplayName("C2: a field-held generator works across replications")
    fun generatorSurvivesMultipleReplications() {
        val m = run(GenModel(Model("multiRep"), useContext = true), length = 5.5, reps = 3)
        assertEquals(15, m.created, "5 agents per replication across 3 replications")
    }
}
