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
import kotlin.test.assertTrue

/**
 *  A holder that resumes a suspension from `replicationEnded()`, timed or not, is refused with an exception: the
 *  replication's events have ended, and the waiting process is terminated after. The suspension then reads as
 *  abandoned, not resumed, as it does when its process is terminated during the wait. The public checks predict the
 *  refusal: the suspension's `canResume` and the entity's `canResumeProcess` are false from `replicationEnded()` on,
 *  and the errors name them.
 */
class ResumeAtReplicationEndedTest {

    private class Holder(parent: ModelElement, val timed: Boolean) : ProcessModel(parent, "holder") {
        lateinit var subject: Subject
        val canResumeDuring = mutableListOf<Boolean>()
        val canResumeProcessDuring = mutableListOf<Boolean>()
        val canResumeAtEnd = mutableListOf<Boolean>()
        val canResumeProcessAtEnd = mutableListOf<Boolean>()
        val resumedAtEnd = mutableListOf<Boolean>()
        val abandonedAtEnd = mutableListOf<Boolean>()
        val resumeErrors = mutableListOf<String>()
        val resumeProcessErrors = mutableListOf<String>()
        var completed = 0

        inner class Subject : Entity("subject") {
            val suspension = Suspension("gate")
            val p: KSLProcess = process("wait") {
                if (timed) suspendForOrTimeout(suspension, 1000.0) else suspendFor(suspension)
                completed++
            }
        }

        override fun initialize() {
            subject = Subject()
            activate(subject.p)
            schedule(::look, 50.0)
        }

        private fun look(event: KSLEvent<Nothing>) {
            canResumeDuring.add(subject.suspension.canResume)
            canResumeProcessDuring.add(subject.canResumeProcess)
        }

        override fun replicationEnded() {
            super.replicationEnded()
            canResumeAtEnd.add(subject.suspension.canResume)
            canResumeProcessAtEnd.add(subject.canResumeProcess)
            try {
                subject.suspension.resume()
            } catch (e: RuntimeException) {
                resumeErrors.add(e.message ?: e.toString())
            }
            try {
                subject.resumeProcess()
            } catch (e: RuntimeException) {
                resumeProcessErrors.add(e.message ?: e.toString())
            }
        }

        override fun afterReplication() {
            super.afterReplication()
            resumedAtEnd.add(subject.suspension.isResumed)
            abandonedAtEnd.add(subject.suspension.isAbandoned)
        }
    }

    private fun run(timed: Boolean): Holder {
        val model = Model("ResumeAtEnd${System.nanoTime()}", autoCSVReports = false)
        val m = Holder(model, timed)
        model.numberOfReplications = 2
        model.lengthOfReplication = 100.0
        model.simulate()
        return m
    }

    private fun check(m: Holder) {
        assertEquals(0, m.completed)
        assertEquals(listOf(true, true), m.canResumeDuring, "canResume while the process waits")
        assertEquals(listOf(false, false), m.canResumeAtEnd, "canResume from replicationEnded")
        assertEquals(listOf(false, false), m.canResumeProcessAtEnd, "canResumeProcess from replicationEnded")
        assertEquals(2, m.resumeErrors.size, "the resume from replicationEnded is refused in each replication")
        assertTrue(m.resumeErrors.all { it.contains("canResume") }, "the refusal names canResume: ${m.resumeErrors}")
        assertEquals(2, m.resumeProcessErrors.size, "resumeProcess from replicationEnded is refused in each replication")
        assertTrue(
            m.resumeProcessErrors.all { it.contains("canResumeProcess") },
            "the refusal names canResumeProcess: ${m.resumeProcessErrors}"
        )
        assertEquals(listOf(false, false), m.resumedAtEnd, "isResumed after the replication")
        assertEquals(listOf(true, true), m.abandonedAtEnd, "isAbandoned after the replication")
    }

    @Test
    @DisplayName("a timed wait resumed from replicationEnded is refused, as canResume predicts, and reads as abandoned")
    fun timed() {
        val m = run(timed = true)
        check(m)
        // a timed wait is never resumed through the entity, even during the run
        assertEquals(listOf(false, false), m.canResumeProcessDuring, "canResumeProcess during a timed wait")
    }

    @Test
    @DisplayName("an untimed wait resumed from replicationEnded is refused, as canResume predicts, and reads as abandoned")
    fun untimed() {
        val m = run(timed = false)
        check(m)
        assertEquals(listOf(true, true), m.canResumeProcessDuring, "canResumeProcess during an untimed wait")
    }
}
