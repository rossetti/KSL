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

package ksl.app.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.animation.AnimationTraceHeader
import ksl.animation.emitters.ResourceAnimationEmitter
import ksl.app.animation.io.AnimationSource
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 *  Preemption, run in the engine and replayed: a unit lent to a preemptor is drawn with the preemptor, not
 *  with the holder it was taken from, and goes back to the holder when the loan ends.
 */
class PreemptionReplayTest {

    private class Shop(parent: ModelElement, capacity: Int) : ProcessModel(parent, "shop") {
        val r = ResourceWithQ(this, "R", capacity = capacity)
        val ids = mutableMapOf<Long, String>()
        val setups = mutableListOf<Shop.() -> Unit>()

        inner class Job(tag: String, prio: Int, val from: Double, val hold: Double, val n: Int, val urgent: Boolean) : Entity(tag) {
            init {
                priority = prio
                ids[id] = tag
            }

            val p: KSLProcess = process("p-$tag") {
                delay(from)
                val a = if (urgent) preempt(r, n, rule = InterruptRule.RESUME) else seize(r, n)
                delay(hold)
                release(a)
            }
        }

        fun job(tag: String, prio: Int, from: Double, hold: Double, n: Int = 1, urgent: Boolean = false) =
            activate(Job(tag, prio, from, hold, n, urgent).p)

        override fun initialize() {
            ids.clear()
            for (s in setups) s()
        }
    }

    /** Runs a shop and returns, for each time in [at], the holders drawn at the resource's units. */
    private fun replay(capacity: Int, at: List<Double>, setup: Shop.() -> Unit): List<List<String>> {
        val sink = object : AnimationSink {
            val events = mutableListOf<AnimationEvent>()
            override val isActive = true
            override fun emit(event: AnimationEvent) {
                events.add(event)
            }
        }
        val model = Model("PreemptionReplay${System.nanoTime()}", autoCSVReports = false)
        model.animationSink = sink
        val shop = Shop(model, capacity)
        shop.r.addAllocationListener(ResourceAnimationEmitter(shop.r))
        shop.setups.add(setup)
        model.lengthOfReplication = 50.0
        model.simulate()
        val m = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = sink.events))
        return at.map { t -> m.resourceUnitsAt("R", t).map { shop.ids.getValue(it) }.sorted() }
    }

    @Test
    fun `a lent unit is drawn with the preemptor, and goes back to its holder`() {
        // L is held 1 to 5, and ends at 14
        val seen = replay(1, listOf(0.5, 2.0, 6.0, 15.0)) {
            job("L", 10, 0.0, 10.0)
            job("P", 0, 1.0, 4.0, urgent = true)
        }
        assertEquals(listOf(listOf("L"), listOf("P"), listOf("L"), emptyList()), seen)
    }

    @Test
    fun `a loan passed on is drawn with each preemptor in turn`() {
        val seen = replay(1, listOf(3.0, 7.0, 12.0)) {
            job("L", 10, 0.0, 20.0)
            job("P1", 1, 1.0, 5.0, urgent = true)
            job("P2", 1, 2.0, 5.0, urgent = true)
        }
        assertEquals(listOf(listOf("P1"), listOf("P2"), listOf("L")), seen)
    }

    @Test
    fun `nested loans go back along the chain`() {
        val seen = replay(1, listOf(3.0, 5.0, 13.0)) {
            job("L", 9, 0.0, 20.0)
            job("M", 5, 1.0, 10.0, urgent = true)
            job("U", 1, 2.0, 2.0, urgent = true)
        }
        assertEquals(listOf(listOf("U"), listOf("M"), listOf("L")), seen)
    }

    @Test
    fun `a preemptor taking two units from two holders is drawn at both`() {
        val seen = replay(2, listOf(0.5, 2.0, 6.0)) {
            job("A", 9, 0.0, 20.0)
            job("B", 9, 0.0, 20.0)
            job("P", 1, 1.0, 4.0, n = 2, urgent = true)
        }
        assertEquals(listOf(listOf("A", "B"), listOf("P", "P"), listOf("A", "B")), seen)
    }
}
