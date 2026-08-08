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
import kotlin.test.assertTrue

/**
 *  Deregistering a suspension is necessary but not sufficient. A resume that has *already been
 *  scheduled* is an event on the calendar, and removing a registration cannot recall it. The window
 *  is real, because resumes are scheduled at zero delay: anything that terminates the entity later
 *  in the same event lands between the scheduling and the firing.
 *
 *  Until termination released the entity, that stale event was caught by accident — the resume paths
 *  are gated on `myCurrentProcess != null`, and termination never cleared it, so a stale resume ran
 *  all the way into a terminated coroutine and aborted the run. Releasing the entity removes that
 *  accidental guard, and would leave a silent no-op in its place — or worse, deliver the wakeup to
 *  whatever process the entity is running *now*.
 *
 *  So the resume itself carries the identity of the process it was scheduled for, and is dropped if
 *  the entity has moved on. The third test is the one that keeps this honest: the check is on
 *  identity, not on state, so a resume that is genuinely wrong for a *live* process must still fail.
 */
class StaleResumeTest {

    private fun <T : ProcessModel> run(name: String, factory: (Model) -> T): T {
        val m = Model(name)
        val p = factory(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        return p
    }

    /**
     *  The subject waits in a hold queue. At time 5 a resume is scheduled for it and the process is
     *  terminated in the same event, before the resume event fires. [activateFresh] decides whether
     *  a new process is started on the released entity in that same instant, which is the case where
     *  a stale wakeup could be misdelivered.
     */
    private class StaleResumeModel(
        parent: ModelElement,
        val activateFresh: Boolean,
    ) : ProcessModel(parent, null) {

        val hq = HoldQueue(this, "HQ")

        lateinit var subject: Worker

        var freshProcessEndTime: Double = Double.NaN
        var freshProcessResumeCount: Int = 0

        inner class Worker : Entity() {
            val doomed = process("doomed") { hold(hq) }

            fun fresh(): KSLProcess = process("fresh") {
                // Two delays, so a misdelivered wakeup would show up either as an early end time or
                // as an extra pass through the loop below.
                delay(10.0)
                freshProcessResumeCount++
                delay(10.0)
                freshProcessResumeCount++
                freshProcessEndTime = time
            }
        }

        override fun initialize() {
            freshProcessEndTime = Double.NaN
            freshProcessResumeCount = 0
            subject = Worker()
            activate(subject.doomed)
            schedule(::resumeThenTerminate, 5.0)
        }

        private fun resumeThenTerminate(event: KSLEvent<Nothing>) {
            // Schedule a wakeup for the doomed process, then kill it before the event fires.
            subject.resumeProcess()
            subject.terminateProcess()
            if (activateFresh) {
                activate(subject.fresh())
            }
        }
    }

    @Test
    @DisplayName("A resume scheduled for a process that then ends is dropped, not delivered")
    fun staleResumeIsDropped() {
        val m = run("staleResumeDropped") { StaleResumeModel(it, activateFresh = false) }
        assertTrue(m.subject.isProcessEnded, "the entity should have been released by the termination")
        assertEquals(0, m.freshProcessResumeCount, "no fresh process was started, so nothing should have run")
    }

    @Test
    @DisplayName("A stale resume is not delivered to the entity's next process")
    fun staleResumeDoesNotDisturbTheNextProcess() {
        val m = run("staleResumeMisdelivery") { StaleResumeModel(it, activateFresh = true) }
        assertEquals(
            2, m.freshProcessResumeCount,
            "the fresh process must pass each of its two delays exactly once; a misdelivered wakeup " +
                "would resume it early and inflate this",
        )
        assertEquals(
            25.0, m.freshProcessEndTime, 1e-9,
            "the fresh process starts at 5.0 and takes 20.0 of its own delays, so it must end at 25.0 " +
                "-- ending early is what a misdelivered wakeup looks like",
        )
    }

    /**
     *  The guard against over-tightening. Dropping applies only when the entity has moved on to a
     *  different process; a wakeup aimed at the process that really is current still reaches the
     *  state machine, and the state machine still rejects it when it is not resumable.
     */
    private class DoubleResumeModel(parent: ModelElement) : ProcessModel(parent, null) {
        val hq = HoldQueue(this, "HQ")
        lateinit var subject: Worker

        inner class Worker : Entity() {
            val work = process("work") {
                hold(hq)
                delay(50.0)
            }
        }

        override fun initialize() {
            subject = Worker()
            activate(subject.work)
            schedule(::resumeTwice, 5.0)
        }

        private fun resumeTwice(event: KSLEvent<Nothing>) {
            // Both wakeups name the live process. The first resumes it; the second arrives while it
            // is mid-delay and must still be rejected by the coroutine state machine.
            hq.removeAndResume(subject)
            subject.resumeProcess()
        }
    }

    @Test
    @DisplayName("A resume that is wrong for the entity's live process still fails")
    fun resumeOfALiveProcessStillFails() {
        val m = Model("liveResumeStillFails")
        DoubleResumeModel(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        val e = runCatching { m.simulate() }.exceptionOrNull()
        val ex = assertNotNull(e, "resuming a live process from an illegal state must still abort the run")
        val msg = assertNotNull(ex.message)
        assertTrue(
            msg.contains("illegal state") || msg.contains("DELAY"),
            "the state machine, not the staleness check, should be what rejects it; was: $msg",
        )
    }
}
