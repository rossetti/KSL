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

import ksl.modeling.queue.Queue
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Pins what every form of `Signal.signal` does today for entities in an untimed `waitFor(signal)`:
 *  which entities resume, in what order, and what the signal's hold queue records. Every form
 *  funnels into `signal(entity)`, so these are the behaviours a change to that function must keep.
 *
 *  Five waiters, W0 to W4, enter the wait at time 0 in that order. At time 5 one signal form is
 *  applied, and each waiter that resumes notes its name and the time.
 */
class SignalCharacterizationTest {

    private class SignalModel(
        parent: ModelElement,
        discipline: Queue.Discipline = Queue.Discipline.FIFO,
        private val priorities: List<Int> = listOf(1, 1, 1, 1, 1),
        private val waitStats: List<Boolean> = listOf(true, true, true, true, true),
        private val action: SignalModel.() -> Unit,
    ) : ProcessModel(parent, "signalModel") {

        val signal = Signal(this, "S", discipline)
        val resumed = mutableListOf<String>()
        val waiters = mutableListOf<Waiter>()
        var timeInQCount: Double = Double.NaN
        var numInQAtEnd: Double = Double.NaN

        inner class Waiter(val tag: String, waitPriority: Int, stats: Boolean) : Entity(tag) {
            val p: KSLProcess = process {
                waitFor(signal, waitPriority = waitPriority, waitStats = stats)
                resumed.add("$tag@$time")
            }
        }

        override fun initialize() {
            resumed.clear()
            waiters.clear()
            for (i in 0 until 5) {
                val w = Waiter("W$i", priorities[i], waitStats[i])
                waiters.add(w)
                activate(w.p)
            }
            schedule(::fire, 5.0)
        }

        private fun fire(event: KSLEvent<Nothing>) {
            action()
        }

        override fun replicationEnded() {
            timeInQCount = signal.waitingQ.timeInQ.withinReplicationStatistic.count
            numInQAtEnd = signal.waitingQ.size.toDouble()
        }
    }

    private fun run(
        discipline: Queue.Discipline = Queue.Discipline.FIFO,
        priorities: List<Int> = listOf(1, 1, 1, 1, 1),
        waitStats: List<Boolean> = listOf(true, true, true, true, true),
        action: SignalModel.() -> Unit,
    ): SignalModel {
        val model = Model("SignalChar${System.nanoTime()}", autoCSVReports = false)
        val sm = SignalModel(model, discipline, priorities, waitStats, action)
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        return sm
    }

    @Test
    @DisplayName("signal(entity) resumes that entity only")
    fun signalOneEntity() {
        val m = run { signal.signal(waiters[2]) }
        assertEquals(listOf("W2@5.0"), m.resumed)
        assertEquals(4.0, m.numInQAtEnd)
    }

    @Test
    @DisplayName("a second signal(entity) at the same instant, for a process that then ends, changes nothing")
    fun signalEntityTwiceThenEnds() {
        val m = run {
            signal.signal(waiters[2])
            signal.signal(waiters[2])
        }
        // The second call happens before W2 has resumed and left the queue, so today it schedules a
        // second resume. W2's process ends at its first resume, so the second is dropped as stale.
        // The harmful case, where the process goes on to another wait, is the double-resume test.
        assertEquals(listOf("W2@5.0"), m.resumed)
    }

    @Test
    @DisplayName("signal(list) resumes the listed waiting entities in list order")
    fun signalList() {
        val m = run { signal.signal(listOf(waiters[3], waiters[1])) }
        assertEquals(listOf("W3@5.0", "W1@5.0"), m.resumed)
    }

    @Test
    @DisplayName("signal(predicate) resumes the matching entities in queue order")
    fun signalPredicate() {
        val m = run { signal.signal({ e: ProcessModel.Entity -> e.name == "W1" || e.name == "W4" }) }
        assertEquals(listOf("W1@5.0", "W4@5.0"), m.resumed)
    }

    @Test
    @DisplayName("signal(rank) resumes the entity at that rank; out of range does nothing")
    fun signalRank() {
        assertEquals(listOf("W3@5.0"), run { signal.signal(3) }.resumed)
        assertEquals(emptyList(), run { signal.signal(7) }.resumed)
    }

    @Test
    @DisplayName("signal(range) resumes the entities at those ranks, read from the queue as it stands")
    fun signalRange() {
        // Each rank is read from the queue at the call; nothing has left it yet (the resumes are
        // events), so ranks 1..3 are W1, W2, W3.
        val m = run { signal.signal(1..3) }
        assertEquals(listOf("W1@5.0", "W2@5.0", "W3@5.0"), m.resumed)
    }

    @Test
    @DisplayName("signalFirst and signalLast resume the head and the tail")
    fun signalFirstLast() {
        assertEquals(listOf("W0@5.0"), run { signal.signalFirst() }.resumed)
        assertEquals(listOf("W4@5.0"), run { signal.signalLast() }.resumed)
    }

    @Test
    @DisplayName("signalAll resumes every waiter in queue order and empties the queue")
    fun signalAll() {
        val m = run { signal.signalAll() }
        assertEquals(listOf("W0@5.0", "W1@5.0", "W2@5.0", "W3@5.0", "W4@5.0"), m.resumed)
        assertEquals(0.0, m.numInQAtEnd)
        assertEquals(5.0, m.timeInQCount)
    }

    @Test
    @DisplayName("a ranked signal queue orders the waiters by their wait priority")
    fun rankedQueueOrder() {
        val m = run(
            discipline = Queue.Discipline.RANKED,
            priorities = listOf(5, 3, 4, 1, 2),
        ) { signal.signalAll() }
        assertEquals(listOf("W3@5.0", "W4@5.0", "W1@5.0", "W2@5.0", "W0@5.0"), m.resumed)
        val first = run(
            discipline = Queue.Discipline.RANKED,
            priorities = listOf(5, 3, 4, 1, 2),
        ) { signal.signalFirst() }
        assertEquals(listOf("W3@5.0"), first.resumed)
    }

    @Test
    @DisplayName("the resume priority orders resumptions at one instant")
    fun resumePriorityOrdersResumptions() {
        val m = run {
            signal.signal(waiters[0], resumePriority = ProcessModel.RESUME_PRIORITY)
            signal.signal(waiters[1], resumePriority = ProcessModel.RESUME_PRIORITY - 1)
        }
        assertEquals(listOf("W1@5.0", "W0@5.0"), m.resumed)
    }

    @Test
    @DisplayName("waitStats false leaves the wait out of the time-in-queue statistic")
    fun waitStatsFalse() {
        val m = run(waitStats = listOf(true, false, true, false, true)) { signal.signalAll() }
        assertEquals(5, m.resumed.size)
        assertEquals(3.0, m.timeInQCount, "only the three waits with waitStats true are observed")
        assertEquals(0.0, m.numInQAtEnd)
    }
}
