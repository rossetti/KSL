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

package ksl.modeling.entity.guiderules

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.animation.emitters.ResourceAnimationEmitter
import ksl.modeling.entity.DelayResult
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ResourceWithQ
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Rules of the entity guide's section on preemption: when the free units and the lower-priority holders' units
 *  together are too few, nothing is taken; under `FINISH` the free units promised to a waiting preemptor stay idle
 *  and go to no one else; units taken go back to their holders newest first; and the animation trace shows a
 *  preemption's units as lent and returned.
 */
class EntityPreemptionRulesTest {

    private fun RuleModel.job(tag: String, r: ResourceWithQ, from: Double, hold: Double, priority: Int, n: Int = 1) =
        actor(tag, priority) {
            delay(from)
            val a = seize(r, n)
            log.add("$tag got@$time")
            delay(hold)
            release(a)
            log.add("$tag done@$time")
        }

    private fun RuleModel.urgent(tag: String, r: ResourceWithQ, from: Double, hold: Double, priority: Int, n: Int, rule: InterruptRule) =
        actor(tag, priority) {
            delay(from)
            val a = preempt(r, n, rule = rule)
            log.add("$tag got@$time")
            delay(hold)
            release(a)
            log.add("$tag done@$time")
        }

    /**
     *  R has 3 units. H (priority 1) holds one from 0 to 20; L1 and L2 (priority 9) hold one each from 0, for 50 and
     *  60. At 1, P (priority 3) preempts 3 units under RESUME: no unit is free and only L1's and L2's can be taken,
     *  2 in all, so nothing is taken and P waits. At 20 H releases: the freed unit with L1's and L2's is enough, and
     *  P takes them, holding L1 and L2 from 20 to 25. They resume with 30 and 40 left, ending at 55 and 65.
     */
    @Test
    @DisplayName("too few free and lower-priority units together: nothing is taken, and preempt waits like seize")
    fun tooFewUnitsTakesNothing() {
        val m = RuleModel("PreemptTooFew")
        val r = ResourceWithQ(m, "R", capacity = 3)
        m.plan {
            job("H", r, 0.0, 20.0, priority = 1)
            job("L1", r, 0.0, 50.0, priority = 9)
            job("L2", r, 0.0, 60.0, priority = 9)
            urgent("P", r, 1.0, 5.0, priority = 3, n = 3, rule = InterruptRule.RESUME)
            at(2.0) { log.add("t2 preemptions=${r.numTimesPreempted}") }
        }
        m.simulate(100.0)
        assertEquals(
            listOf(
                "H got@0.0", "L1 got@0.0", "L2 got@0.0", "t2 preemptions=0",
                "H done@20.0", "P got@20.0", "P done@25.0", "L1 done@55.0", "L2 done@65.0"
            ),
            m.log
        )
    }

    /**
     *  R has 2 units. L (priority 9) holds one from 0 to 10. At 1, P (priority 1) preempts 2 units under FINISH: it
     *  is promised the free unit and waits for L's. At 2, S (priority 5) seizes 1 unit; the promised unit is idle
     *  but goes to no one else, so S waits. At 10 L releases and P takes both units; S is served at P's release, 15.
     */
    @Test
    @DisplayName("under FINISH the free units promised to the preemptor stay idle and go to no one else")
    fun finishPromisedUnitsStayIdle() {
        val m = RuleModel("PreemptFinishIdle")
        val r = ResourceWithQ(m, "R", capacity = 2)
        m.plan {
            job("L", r, 0.0, 10.0, priority = 9)
            urgent("P", r, 1.0, 5.0, priority = 1, n = 2, rule = InterruptRule.FINISH)
            job("S", r, 2.0, 5.0, priority = 5)
            at(5.0) { log.add("t5 busy=${r.numBusy}") }
        }
        m.simulate(100.0)
        assertEquals(
            listOf("L got@0.0", "t5 busy=1", "L done@10.0", "P got@10.0", "P done@15.0", "S got@15.0", "S done@20.0"),
            m.log
        )
    }

    /**
     *  R has 3 units. H (priority 1) holds one throughout. L1 (priority 9) seizes one at 0, and L2 (priority 9) one
     *  at 1, each for 50. At 2, P1 (priority 5) preempts 2 units under RESUME, taking L1's and L2's, and works with
     *  `delayOrInterrupt`. At 3, P2 (priority 2) preempts 1 unit, taking one of P1's; P1 is told and releases at
     *  once. The units go back the last taken first: P1 took L2's unit (the most recently allocated) first and
     *  L1's last, so P1's one unit left goes back to L1 at 3, and L2's comes back at P2's release, 7. So L1, held
     *  from 2 to 3, ends at 51, and L2, held from 2 to 7, at 56.
     */
    @Test
    @DisplayName("the units a preemptor took go back to their holders the last taken first")
    fun unitsGoBackLastTakenFirst() {
        val m = RuleModel("PreemptNewestFirst")
        val r = ResourceWithQ(m, "R", capacity = 3)
        m.plan {
            actor("H", 1) { seize(r); delay(1000.0) }
            job("L1", r, 0.0, 50.0, priority = 9)
            job("L2", r, 1.0, 50.0, priority = 9)
            actor("P1", 5) {
                delay(2.0)
                val a = preempt(r, 2, rule = InterruptRule.RESUME)
                log.add("P1 got@$time")
                val res = delayOrInterrupt(20.0, uses = listOf(a))
                log.add("P1 ${if (res is DelayResult.Interrupted) "interrupted" else "completed"}@$time")
                release(a)
            }
            urgent("P2", r, 3.0, 4.0, priority = 2, n = 1, rule = InterruptRule.RESUME)
        }
        m.simulate(100.0)
        assertTrue("P1 interrupted@3.0" in m.log, "P2 takes one of P1's units at 3; log = ${m.log}")
        assertTrue("P2 done@7.0" in m.log, "log = ${m.log}")
        assertEquals(listOf("L1 done@51.0", "L2 done@56.0"), m.log.filter { it.startsWith("L") && "done" in it })
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) {
            events.add(event)
        }
    }

    /**
     *  R has one unit, held by L (priority 9) from 0 to 10. At 2, P (priority 1) preempts it under RESUME and
     *  releases at 3. The trace shows the unit lent from L to P at 2 and returned to L at 3.
     */
    @Test
    @DisplayName("the animation trace shows a preemption's units as ResourceUnitsLent and ResourceUnitsReturned")
    fun traceShowsUnitsLentAndReturned() {
        val m = RuleModel("PreemptTrace")
        val r = ResourceWithQ(m, "R", capacity = 1)
        r.addAllocationListener(ResourceAnimationEmitter(r))
        val sink = CollectingSink()
        m.model.animationSink = sink
        val ids = mutableMapOf<Long, String>()
        m.plan {
            ids[job("L", r, 0.0, 10.0, priority = 9).id] = "L"
            ids[urgent("P", r, 2.0, 1.0, priority = 1, n = 1, rule = InterruptRule.RESUME).id] = "P"
        }
        m.simulate(100.0)
        val seen = sink.events.mapNotNull {
            when (it) {
                is AnimationEvent.ResourceUnitsLent -> if (it.resourceName == "R")
                    "${it.simTime} lent ${it.amount} from ${ids[it.lenderEntityId]} to ${ids[it.borrowerEntityId]}" else null
                is AnimationEvent.ResourceUnitsReturned -> if (it.resourceName == "R")
                    "${it.simTime} returned ${it.amount} to ${ids[it.entityId]}" else null
                else -> null
            }
        }
        assertEquals(listOf("2.0 lent 1 from L to P", "3.0 returned 1 to L"), seen)
    }
}
