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

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  A resume is an event, so two resumes scheduled for one entity at one instant would both fire:
 *  the first moves the process on, and when it moves on to another wait in the same instant, the
 *  second wakes it from that wait early. These tests first recorded that defect; the double-resume
 *  guard changed them to the fixed behaviour. A repeated signal now does nothing, and resuming an
 *  entity through a queue it is not in is refused.
 */
class DoubleResumeCharacterizationTest {

    private abstract class Base(parent: ModelElement) : ProcessModel(parent, "doubleResume") {
        val log = mutableListOf<String>()
        fun note(text: String) { log.add("$text@$time") }
    }

    private fun run(build: (Model) -> Base): List<String> {
        val model = Model("DoubleResume${System.nanoTime()}", autoCSVReports = false)
        val base = build(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        return base.log
    }

    /**
     *  The subject waits for a signal, then holds in a hold queue that nobody releases until 50.
     *  At time 5 it is signalled twice, by the given action.
     */
    private class SignalTwice(parent: ModelElement, val twice: SignalTwice.() -> Unit) : Base(parent) {
        val signal = Signal(this, "S")
        val hq = HoldQueue(this, "HQ")
        lateinit var subject: Subject

        inner class Subject : Entity("subject") {
            val p: KSLProcess = process {
                waitFor(signal)
                note("signalled")
                hold(hq)
                note("released")
            }
        }

        override fun initialize() {
            log.clear()
            subject = Subject()
            activate(subject.p)
            schedule(::fire, 5.0)
            schedule(::release, 50.0)
        }

        private fun fire(event: KSLEvent<Nothing>) = twice()

        private fun release(event: KSLEvent<Nothing>) {
            if (hq.contains(subject)) hq.removeAndResume(subject)
        }
    }

    @Test
    @DisplayName("signalAll twice at one instant resumes the entity once; it stays in its next hold")
    fun signalAllTwice() {
        val log = run { SignalTwice(it) { signal.signalAll(); signal.signalAll() } }
        assertEquals(listOf("signalled@5.0", "released@50.0"), log)
    }

    @Test
    @DisplayName("a list naming the entity twice resumes it once; it stays in its next hold")
    fun listNamingTwice() {
        val log = run { SignalTwice(it) { signal.signal(listOf(subject, subject)) } }
        assertEquals(listOf("signalled@5.0", "released@50.0"), log)
    }

    @Test
    @DisplayName("control, one signal leaves the entity in its hold until it is released")
    fun singleSignalControl() {
        val log = run { SignalTwice(it) { signal.signalAll() } }
        assertEquals(listOf("signalled@5.0", "released@50.0"), log)
    }

    /**
     *  The subject holds in queue B. At time 5 queue A, which the subject is not in, is asked to
     *  remove and resume it.
     */
    private class WrongQueue(parent: ModelElement, val immediate: Boolean) : Base(parent) {
        val qA = HoldQueue(this, "A")
        val qB = HoldQueue(this, "B")
        lateinit var subject: Subject
        var stillInB: Boolean = false

        inner class Subject : Entity("subject") {
            val p: KSLProcess = process {
                hold(qB)
                note("resumed")
                stillInB = qB.contains(this@Subject)
            }
        }

        override fun initialize() {
            log.clear()
            subject = Subject()
            activate(subject.p)
            schedule(::fire, 5.0)
        }

        private fun fire(event: KSLEvent<Nothing>) {
            if (immediate) qA.removeAndImmediateResume(subject) else qA.removeAndResume(subject)
        }
    }

    @Test
    @DisplayName("removeAndResume of an entity not in the queue is refused, naming the queue")
    fun removeAndResumeNotInQueue() {
        val e = assertFailsWith<IllegalArgumentException> { run { WrongQueue(it, immediate = false) } }
        assertTrue(e.message!!.contains("not in the hold queue A"), e.message)
    }

    @Test
    @DisplayName("removeAndImmediateResume of an entity not in the queue is refused, naming the queue")
    fun removeAndImmediateResumeNotInQueue() {
        val e = assertFailsWith<IllegalArgumentException> { run { WrongQueue(it, immediate = true) } }
        assertTrue(e.message!!.contains("not in the hold queue A"), e.message)
    }
}
