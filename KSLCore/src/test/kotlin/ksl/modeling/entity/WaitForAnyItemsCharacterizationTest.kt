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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Pins `waitForAnyItems` as it is today: what a waiting receiver takes when items arrive, how a
 *  predicate filters, and how the receiver request selector chooses among waiting receivers. The
 *  wake-up of waiting requests is the code a timed items wait changes.
 */
class WaitForAnyItemsCharacterizationTest {

    private abstract class Base(parent: ModelElement) : ProcessModel(parent, "anyItems") {
        val log = mutableListOf<String>()
        fun note(text: String) { log.add("$text@$time") }
        inner class Item(val tag: String) : QObject() {
            override fun toString(): String = tag
        }
    }

    private fun run(build: (Model) -> Base): List<String> {
        val model = Model("AnyItems${System.nanoTime()}", autoCSVReports = false)
        val base = build(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        return base.log
    }

    /** One receiver for any item tagged "a"; items arrive one at a time or together. */
    private class OneReceiver(parent: ModelElement, val together: Boolean, val early: Boolean) : Base(parent) {
        val q = BlockingQueue<Item>(this, name = "Q")

        inner class Receiver : Entity() {
            val p: KSLProcess = process {
                if (early) delay(10.0)
                val got = waitForAnyItems(q, { it.tag.startsWith("a") })
                note("got ${got.joinToString(",")}")
            }
        }

        inner class Sender : Entity() {
            val p: KSLProcess = process {
                delay(5.0)
                if (together) {
                    sendItems(listOf(Item("b1"), Item("a1"), Item("a2")), q)
                } else {
                    send(Item("b1"), q)
                    send(Item("a1"), q)
                    send(Item("a2"), q)
                }
            }
        }

        override fun initialize() {
            log.clear()
            activate(Receiver().p)
            activate(Sender().p)
        }
    }

    @Test
    @DisplayName("a waiting receiver takes every matching item present when it is woken")
    fun waitingReceiverTakesMatching() {
        // Sent one at a time: the receiver is woken by the first matching item and, when it runs,
        // takes every matching item present by then.
        assertEquals(listOf("got a1,a2@5.0"), run { OneReceiver(it, together = false, early = false) })
        assertEquals(listOf("got a1,a2@5.0"), run { OneReceiver(it, together = true, early = false) })
    }

    @Test
    @DisplayName("a receiver arriving after the items takes them without waiting; non-matching items stay")
    fun receiverArrivingLater() {
        assertEquals(listOf("got a1,a2@10.0"), run { OneReceiver(it, together = true, early = true) })
    }

    /**
     *  Two receivers, the first wanting "a" items and the second "b" items. One "b" item arrives.
     *  With the default selector the head request cannot be filled, so nobody is woken; with the
     *  first-fillable selector the second receiver is.
     */
    private class TwoReceivers(parent: ModelElement, firstFillable: Boolean) : Base(parent) {
        val q = BlockingQueue<Item>(this, name = "Q").also {
            if (firstFillable) it.receiverRequestSelector = it.FirstFillableRequest()
        }

        inner class Receiver(val who: String, val prefix: String) : Entity() {
            val p: KSLProcess = process {
                val got = waitForAnyItems(q, { it.tag.startsWith(prefix) })
                note("$who got ${got.joinToString(",")}")
            }
        }

        inner class Sender : Entity() {
            val p: KSLProcess = process {
                delay(5.0)
                send(Item("b1"), q)
                delay(5.0)
                send(Item("a1"), q)
            }
        }

        override fun initialize() {
            log.clear()
            activate(Receiver("RA", "a").p)
            activate(Receiver("RB", "b").p)
            activate(Sender().p)
        }
    }

    @Test
    @DisplayName("the default selector serves only the head request")
    fun defaultSelectorHeadOnly() {
        // The "b" item cannot fill RA at the head, so RB is not woken by it. When "a1" arrives RA is
        // filled; the selection then reaches RB, which takes the "b" item waiting since time 5.
        assertEquals(listOf("RA got a1@10.0", "RB got b1@10.0"), run { TwoReceivers(it, firstFillable = false) })
    }

    @Test
    @DisplayName("the first-fillable selector serves a request behind one that cannot be filled")
    fun firstFillableSelector() {
        assertEquals(listOf("RB got b1@5.0", "RA got a1@10.0"), run { TwoReceivers(it, firstFillable = true) })
    }
}
