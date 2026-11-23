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

package ksl.modeling.agent.guiderules

import ksl.modeling.agent.AgentLike
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.AgentResource
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.MovableAgentResource
import ksl.modeling.agent.Point2D
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.Resource
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Rules of the agent guide's "...make an agent a seizable resource" (`ksl-agent.md`), checked case by case:
 *
 *  - any resource has `goOffShift` and `goOnShift`, not only an agent resource: agent resources, movable agent
 *    resources, resources with a queue and pool members are checked elsewhere; this class checks a plain `Resource`,
 *    seized through a queue of the model's own;
 *  - a behavior, `cyclicBehavior { }` or `behavior { }`, runs as a process every replication: a behavior of a plain
 *    resource is checked elsewhere; this class checks both forms on an agent resource and on a movable agent
 *    resource, over two replications.
 */
class AgentSeizableResourceCasesTest {

    private class Floor(parent: ModelElement) : AgentModel(parent, "Floor") {
        val world: Context<AgentLike> = Context("world")
        val space: ContinuousProjection<AgentLike> = ContinuousProjection(world, 0.0..100.0, 0.0..100.0)
        val desk = AgentResource(this, "Desk")
        val forklift = MovableAgentResource(this, space, initPosition = Point2D(0.0, 0.0), name = "Forklift")
        val plain = Resource(this, "Plain")
        val plainQ = RequestQ(this, "PlainQ")
        val log = mutableListOf<String>()
        val setups = mutableListOf<Floor.() -> Unit>()

        inner class Actor(tag: String, val body: suspend ksl.modeling.entity.KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) { body() }
        }

        fun actor(tag: String, body: suspend ksl.modeling.entity.KSLProcessBuilder.() -> Unit) =
            Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            super.initialize()
            for (s in setups) s()
        }
    }

    private fun run(reps: Int, length: Double, declare: Floor.() -> Unit = {}, setup: Floor.() -> Unit = {}): Floor {
        val model = Model("SeizableCases${System.nanoTime()}", autoCSVReports = false)
        val f = Floor(model)
        f.declare()
        f.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.simulate()
        return f
    }

    /**
     *  H holds the plain resource from 0 to 5. It goes off shift at 2, under IGNORE: H finishes, and W, asking at 3,
     *  waits until it goes on shift at 8.
     */
    @Test
    @DisplayName("a plain Resource goes off shift and back on: the holder finishes, and a request waits for goOnShift")
    fun plainResourceShifts() {
        val f = run(reps = 1, length = 20.0) {
            actor("H") { val a = seize(plain, queue = plainQ); delay(5.0); release(a); log.add("H done@$time") }
            actor("W") { delay(3.0); val a = seize(plain, queue = plainQ); log.add("W got@$time"); release(a) }
            at(2.0) { plain.goOffShift(); log.add("off=${plain.isOffShift} cap=${plain.capacity}") }
            at(8.0) { plain.goOnShift(); log.add("off=${plain.isOffShift} cap=${plain.capacity}") }
        }
        assertEquals(listOf("off=true cap=0", "H done@5.0", "off=false cap=1", "W got@8.0"), f.log)
    }

    @Test
    @DisplayName("an agent resource's cyclic behavior runs as a process from time 0 of every replication")
    fun agentResourceCyclicBehaviorEveryReplication() {
        val f = run(reps = 2, length = 25.0, declare = {
            desk.cyclicBehavior { log.add("desk cycle@$time"); delay(10.0) }
        })
        assertEquals(List(2) { listOf("desk cycle@0.0", "desk cycle@10.0", "desk cycle@20.0") }.flatten(), f.log)
    }

    @Test
    @DisplayName("an agent resource's one-shot behavior runs once, from time 0, in every replication")
    fun agentResourceBehaviorEveryReplication() {
        val f = run(reps = 2, length = 25.0, declare = {
            desk.behavior { log.add("desk start@$time"); delay(3.0); log.add("desk end@$time") }
        })
        assertEquals(List(2) { listOf("desk start@0.0", "desk end@3.0") }.flatten(), f.log)
    }

    @Test
    @DisplayName("a movable agent resource's cyclic behavior runs as a process from time 0 of every replication")
    fun movableAgentResourceCyclicBehaviorEveryReplication() {
        val f = run(reps = 2, length = 25.0, declare = {
            forklift.cyclicBehavior { log.add("forklift cycle@$time"); delay(10.0) }
        })
        assertEquals(List(2) { listOf("forklift cycle@0.0", "forklift cycle@10.0", "forklift cycle@20.0") }.flatten(), f.log)
    }

    @Test
    @DisplayName("a movable agent resource's one-shot behavior runs once, from time 0, in every replication")
    fun movableAgentResourceBehaviorEveryReplication() {
        val f = run(reps = 2, length = 25.0, declare = {
            forklift.behavior { log.add("forklift start@$time"); delay(3.0); log.add("forklift end@$time") }
        })
        assertEquals(List(2) { listOf("forklift start@0.0", "forklift end@3.0") }.flatten(), f.log)
    }
}
