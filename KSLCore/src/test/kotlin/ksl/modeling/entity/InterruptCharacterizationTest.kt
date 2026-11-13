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

package ksl.modeling.entity

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.modeling.spatial.DistancesModel
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Pins what an interrupted delay does today where `InterruptDelayTest` does not look: the animation
 *  trace of an interrupted delay, an interrupt of a delay inside a called sub-process, the errors for
 *  an interrupt aimed at a moving or a yielding entity, and an entity still frozen when a replication
 *  ends. The freeze under these is about to be rewritten.
 */
class InterruptCharacterizationTest {

    private class IModel(parent: ModelElement) : ProcessModel(parent, "interrupts") {
        private val dm = DistancesModel()
        val locA = dm.Location("A")
        val locB = dm.Location("B")

        init {
            dm.addDistance(locA, locB, 100.0, symmetric = true)
            dm.defaultVelocity = ConstantRV(10.0)
            spatialModel = dm
        }

        val log = mutableListOf<String>()
        val setups = mutableListOf<IModel.() -> Unit>()

        inner class Actor(val tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(reps: Int = 1, length: Double = 100.0, sink: AnimationSink? = null, setup: IModel.() -> Unit): IModel {
        val model = Model("Interrupts${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = IModel(model)
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.simulate()
        return m
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    @Test
    @DisplayName("the trace of an interrupted delay: DelayStarted at the start, nothing at the freeze, another DelayStarted at the reschedule, one DelayEnded")
    fun traceOfAnInterruptedDelay() {
        val sink = CollectingSink()
        lateinit var worker: IModel.Actor
        run(sink = sink) {
            worker = actor("W") { delay(10.0, suspensionName = "work") }
            actor("I") { delay(4.0); interruptDelayAndContinue(worker.p, "work", interruptTime = 5.0) }
        }
        val seen = sink.events.mapNotNull {
            when (it) {
                is AnimationEvent.DelayStarted -> if (it.entityId == worker.id) "DelayStarted@${it.simTime} ends ${it.arrivalTime}" else null
                is AnimationEvent.DelayEnded -> if (it.entityId == worker.id) "DelayEnded@${it.simTime}" else null
                else -> null
            }
        }
        assertEquals(listOf("DelayStarted@0.0 ends 10.0", "DelayStarted@9.0 ends 15.0", "DelayEnded@15.0"), seen)
    }

    @Test
    @DisplayName("an interrupt of a delay inside a called sub-process: the sub-process's delay freezes and its caller resumes when it completes")
    fun interruptInsideASubProcess() {
        val m = run {
            val worker = Actor("S") { delay(10.0, suspensionName = "work"); log.add("sub done@$time") }
            actor("C") { waitFor(worker.p); log.add("caller@$time") }
            actor("I") { delay(4.0); interruptDelayAndContinue(worker.p, "work", interruptTime = 5.0) }
        }
        assertEquals(listOf("sub done@15.0", "caller@15.0"), m.log)
    }

    @Test
    @DisplayName("an interrupt aimed at a moving entity is an error that says it is moving")
    fun interruptAMovingEntity() {
        var error: String? = null
        run {
            val mover = actor("M") { entity.currentLocation = locA; moveTo(locB, velocity = 10.0) }
            actor("I") {
                delay(4.0)
                try {
                    interruptDelay(mover.p, "work", 5.0, postInterruptDelayTime = 1.0)
                } catch (e: IllegalStateException) {
                    error = e.message
                } catch (e: IllegalArgumentException) {
                    error = e.message
                }
            }
        }
        val msg = assertNotNull(error)
        assertTrue(msg.contains("it is moving"), msg)
    }

    @Test
    @DisplayName("an interrupt aimed at a yielding entity is an error that says it is yielding")
    fun interruptAYieldingEntity() {
        var error: String? = null
        run {
            val yielder = actor("Y") { delay(5.0, delayPriority = 1); yield(yieldPriority = KSLEvent.LOW_PRIORITY) }
            actor("I") {
                delay(5.0)
                try {
                    interruptDelay(yielder.p, "work", 5.0, postInterruptDelayTime = 1.0)
                } catch (e: IllegalStateException) {
                    error = e.message
                } catch (e: IllegalArgumentException) {
                    error = e.message
                }
            }
        }
        val msg = assertNotNull(error)
        assertTrue(msg.contains("it is yielding"), msg)
    }

    @Test
    @DisplayName("an entity frozen at the end of a replication: nothing of it fires in the next one")
    fun frozenAtTheEndOfAReplication() {
        val m = run(reps = 3, length = 50.0) {
            val worker = actor("W") { delay(10.0, suspensionName = "work"); log.add("worker done@$time") }
            actor("I") {
                delay(4.0)
                interruptDelayAndContinue(worker.p, "work", interruptTime = 100.0)
                log.add("interrupt over@$time")
            }
            actor("Later") { delay(30.0); log.add("later@$time") }
        }
        // the log is cleared at each start, so this is the last replication; each one is the same
        assertEquals(listOf("later@30.0"), m.log)
    }
}
