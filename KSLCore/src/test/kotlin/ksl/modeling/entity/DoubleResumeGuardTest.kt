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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  A suspension point receives at most one resume, from any source. A second resume scheduled
 *  before the first has run is refused where it is made, naming both; the check function tells a
 *  caller beforehand. The guard is cleared when the entity's process ends, so a reused entity is
 *  not refused because of a resume meant for its previous process.
 */
class DoubleResumeGuardTest {

    /** The subject holds in a hold queue; at time 5, [poke] runs. */
    private class GuardModel(parent: ModelElement, val poke: GuardModel.() -> Unit) : ProcessModel(parent, "guard") {
        val hq = HoldQueue(this, "HQ")
        lateinit var subject: Subject
        val log = mutableListOf<String>()
        var error: String? = null

        inner class Subject : Entity("subject") {
            val p: KSLProcess = process("first") {
                hold(hq)
                log.add("resumed@$time")
            }

            fun fresh(): KSLProcess = process("fresh") {
                hold(hq)
                log.add("fresh resumed@$time")
            }
        }

        override fun initialize() {
            log.clear()
            subject = Subject()
            activate(subject.p)
            schedule(::fire, 5.0)
        }

        private fun fire(event: KSLEvent<Nothing>) {
            try {
                poke()
            } catch (e: IllegalStateException) {
                error = e.message
            }
        }

        fun later(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t)
        }
    }

    private fun run(poke: GuardModel.() -> Unit): GuardModel {
        val model = Model("Guard${System.nanoTime()}", autoCSVReports = false)
        val m = GuardModel(model, poke)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("two resumeProcess() calls: the second is refused at the call, naming both resumes")
    fun twoExternalResumes() {
        val m = run {
            subject.resumeProcess()
            subject.resumeProcess()
        }
        val msg = assertNotNull(m.error)
        assertTrue(msg.contains("already has a pending resume"), msg)
        assertEquals(2, Regex("EXTERNAL").findAll(msg).count(), "both resumes are named; was: $msg")
        assertEquals(listOf("resumed@5.0"), m.log, "the first resume still happens, once")
    }

    @Test
    @DisplayName("a queue's resume then resumeProcess(): the second is refused, naming the queue's")
    fun queueThenExternal() {
        val m = run {
            hq.removeAndResume(subject)
            subject.resumeProcess()
        }
        val msg = assertNotNull(m.error)
        assertTrue(msg.contains("HOLD_QUEUE") && msg.contains("EXTERNAL"), msg)
    }

    @Test
    @DisplayName("canResumeProcess is true for a suspended entity and false while a resume is pending")
    fun checkFunction() {
        val seen = mutableListOf<Boolean>()
        val m = run {
            seen.add(subject.canResumeProcess)
            hq.removeAndResume(subject)
            seen.add(subject.canResumeProcess)
        }
        assertEquals(listOf(true, false), seen)
        assertNull(m.error)
        assertEquals(listOf("resumed@5.0"), m.log)
    }

    @Test
    @DisplayName("a terminated entity with a pending resume, reused, is not refused, and the stale resume is dropped")
    fun terminatedWithPendingResumeIsReusable() {
        val m = run {
            hq.removeAndResume(subject)
            subject.terminateProcess()
            activate(subject.fresh())
            later(10.0) {
                if (subject.canResumeProcess) hq.removeAndResume(subject) else log.add("refused")
            }
        }
        assertNull(m.error)
        assertEquals(listOf("fresh resumed@15.0"), m.log, "the fresh process is resumed by its own resume, not the stale one")
    }
}
