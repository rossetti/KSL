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

package ksl.animation

import ksl.modeling.agent.AgentModel
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourceFailureBasis
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  A resource's own entities, the one that waits out its declared failures and the one that runs its behavior,
 *  are the resource's, not entities of the model: the animation inventory lists them as internal, for a plain
 *  `Resource` and for its subclasses alike; a trace that shows them draws no nudge to declare them; and their
 *  events are captured, because a behavior that seizes its resource is part of what a replay shows.
 */
class ResourceInternalEntitiesInventoryTest {

    private val internal = setOf("ResourceFailures", "ResourceBehavior")

    @Test
    @DisplayName("a plain resource adds no entity type of the model's own to the inventory")
    fun plainResource() {
        val model = Model("Inventory${System.nanoTime()}", autoCSVReports = false)
        Resource(model, "Worker")
        val types = model.animationInventory().entityTypes
        assertEquals(emptyList(), types.filter { !it.isInternal }.map { it.typeName })
        assertEquals(internal, types.filter { it.isInternal && it.include }.map { it.typeName }.toSet())
    }

    /** A resource with a queue, a behavior and declared failures, so both of its entities appear in a trace. */
    private class Clinic(parent: ModelElement) : ProcessModel(parent, "Clinic") {
        val doctor = ResourceWithQ(this, "Doctor")

        init {
            doctor.cyclicBehavior {
                delay(10.0)
                doctor.goOffShift()
                delay(2.0)
                doctor.goOnShift()
            }
            doctor.failures(ConstantRV(7.0), ConstantRV(1.0), ResourceFailureBasis.CALENDAR_TIME)
        }
    }

    @Test
    @DisplayName("a resource subclass with a behavior and failures: both listed as internal, and its trace draws no nudge")
    fun subclassWithBehaviorAndFailures() {
        val events = mutableListOf<AnimationEvent>()
        val model = Model("Inventory${System.nanoTime()}", autoCSVReports = false)
        model.animationSink = object : AnimationSink {
            override val isActive = true
            override fun emit(event: AnimationEvent) {
                events.add(event)
            }
        }
        Clinic(model)
        val inventory = model.animationInventory()
        val types = inventory.entityTypes
        assertEquals(emptyList(), types.filter { !it.isInternal }.map { it.typeName })
        assertEquals(internal, types.filter { it.isInternal && it.include }.map { it.typeName }.toSet())
        model.numberOfReplications = 1
        model.lengthOfReplication = 30.0
        model.simulate()
        val created = events.filterIsInstance<AnimationEvent.EntityCreated>().map { it.entityType }.toSet()
        assertTrue(created.containsAll(internal), "both entities must appear in the trace for this to test anything: $created")
        assertEquals(emptyList(), inventory.validateTrace(events).issues.map { it.message })
    }

    /** A doctor whose behavior takes it for a break by seizing it, as `cyclicBehavior` documents. */
    private class Breaks(parent: ModelElement) : ProcessModel(parent, "Breaks") {
        val doctor = ResourceWithQ(this, "Doctor")

        init {
            doctor.cyclicBehavior {
                delay(10.0)
                val a = seize(doctor)
                delay(5.0)
                release(a)
            }
        }
    }

    @Test
    @DisplayName("a behavior that seizes its resource is captured: its seize, allocation and release reach the trace")
    fun behaviorSeizeIsCaptured() {
        val model = Model("Inventory${System.nanoTime()}", autoCSVReports = false)
        Breaks(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 30.0
        val trace = java.nio.file.Files.createTempFile("behavior-capture", ".atf")
        try {
            val capture = AnimationCapture.toFile(model, trace)
            model.simulate()
            capture.close()
            val events = TraceFileReader.readAll(trace).second
            val behavior = events.filterIsInstance<AnimationEvent.EntityCreated>()
                .filter { it.entityType == "ResourceBehavior" }.map { it.entityId }.toSet()
            assertTrue(behavior.isNotEmpty(), "the behavior's entity is in the trace")
            val allocated = events.filterIsInstance<AnimationEvent.SeizeAllocated>().filter { it.entityId in behavior }
            val released = events.filterIsInstance<AnimationEvent.Released>().filter { it.entityId in behavior }
            assertEquals(listOf(10.0, 25.0), allocated.map { it.simTime }, "the behavior's allocations")
            assertEquals(listOf(15.0, 30.0), released.map { it.simTime }.filter { it <= 30.0 }, "the behavior's releases")
        } finally {
            java.nio.file.Files.deleteIfExists(trace)
        }
    }

    private class Agents(parent: ModelElement) : AgentModel(parent, "Agents")

    @Test
    @DisplayName("an agent model gains no included entity type from its superclasses")
    fun agentModel() {
        val model = Model("Inventory${System.nanoTime()}", autoCSVReports = false)
        Agents(model)
        val types = model.animationInventory().entityTypes
        assertEquals(emptyList(), types.filter { !it.isInternal }.map { it.typeName })
    }
}
