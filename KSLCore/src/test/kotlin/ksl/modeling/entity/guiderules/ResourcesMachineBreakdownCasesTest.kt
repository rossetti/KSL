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

import ksl.modeling.entity.Allocation
import ksl.modeling.entity.AllocationListenerIfc
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourceFailureBasis
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  The rules of the resources tutorial's case 2, "A machine that breaks down" (`ksl-resources-tutorial.md`), checked
 *  case by case:
 *
 *  - declared failures fail and repair the machine by themselves, every replication, whatever wears the machine:
 *    the busy time banked and the uses counted in one replication do not carry into the next (calendar time is
 *    checked elsewhere);
 *  - failing after a number of jobs fails the machine between jobs, never during one, under every interrupt rule
 *    (FINISH and RESUME are checked elsewhere; this class checks RESTART).
 */
class ResourcesMachineBreakdownCasesTest {

    /** Logs each failure and repair of [r] in the model's log. */
    private fun RuleModel.watch(r: Resource) {
        r.addAllocationListener(object : AllocationListenerIfc {
            override fun allocate(allocation: Allocation) {}
            override fun deallocate(allocation: Allocation) {}
            override fun failureStateChanged(resource: Resource) {
                log.add("${resource.name} ${if (resource.isFailed) "fail" else "repair"}@$time")
            }
        })
    }

    private fun RuleModel.job(tag: String, r: ResourceWithQ, from: Double, hold: Double) = actor(tag) {
        delay(from)
        val a = seize(r)
        log.add("$tag got@$time")
        delay(hold)
        release(a)
        log.add("$tag done@$time")
    }

    /** Runs [reps] replications of [length] and returns each replication's log, read just before its end. */
    private fun RuleModel.logs(length: Double, reps: Int): List<List<String>> {
        val all = mutableListOf<List<String>>()
        plan { at(length - 0.01) { all.add(log.toList()) } }
        simulate(length, reps)
        return all
    }

    /**
     *  USES, every 3 jobs, repaired in 1. Jobs of 1 start at 0, 2, 4 and 6.5: the third release, at 5, fails the
     *  machine; the fourth, at 7.5, is one use toward the next failure when the replication ends at 10. Were that use
     *  carried over, the next replication's machine would fail at 3, after its second job.
     */
    @Test
    @DisplayName("USES: the count of jobs starts again each replication; a use left over is not carried into the next")
    fun usesStartAgainEachReplication() {
        val m = RuleModel("UsesEachRep")
        val machine = ResourceWithQ(m, "Machine")
        machine.failures(ConstantRV(3.0), ConstantRV(1.0), ResourceFailureBasis.USES)
        m.watch(machine)
        m.plan { for ((i, t) in listOf(0.0, 2.0, 4.0, 6.5).withIndex()) job("J$i", machine, t, 1.0) }
        val logs = m.logs(10.0, reps = 2)
        val one = listOf(
            "J0 got@0.0", "J0 done@1.0", "J1 got@2.0", "J1 done@3.0", "J2 got@4.0", "Machine fail@5.0", "J2 done@5.0",
            "Machine repair@6.0", "J3 got@6.5", "J3 done@7.5"
        )
        assertEquals(listOf(one, one), logs)
        val failures = m.model.getModelElement("Machine:NumFailures") as Counter
        assertEquals(1.0, failures.acrossReplicationStatistic.average, 1e-12)
        assertEquals(0.0, failures.acrossReplicationStatistic.variance, 1e-12)
    }

    /**
     *  BUSY_TIME, every 5 busy minutes, repaired in 1. One job, from 0 to 3, banks 3 busy minutes, and the replication
     *  ends at 10 with the machine idle. Were the bank carried over, the next replication's job would fail the machine
     *  at 2.
     */
    @Test
    @DisplayName("BUSY_TIME: the busy time banked starts again each replication; time left over is not carried into the next")
    fun busyTimeStartsAgainEachReplication() {
        val m = RuleModel("BusyEachRep")
        val machine = ResourceWithQ(m, "Machine")
        machine.failures(ConstantRV(5.0), ConstantRV(1.0), ResourceFailureBasis.BUSY_TIME)
        m.watch(machine)
        m.plan { job("A", machine, 0.0, 3.0) }
        val logs = m.logs(10.0, reps = 2)
        val one = listOf("A got@0.0", "A done@3.0")
        assertEquals(listOf(one, one), logs)
    }

    /**
     *  USES, every job, repaired in 2, under RESTART. A holds the machine from 0 to 5; B asks at 1 for 1. A's release
     *  at 5 completes the use: the machine fails then, after A's job and before B is given the unit, so no job is
     *  restarted. B is served at the repair, 7, and its release at 8 fails the machine again.
     */
    @Test
    @DisplayName("USES under RESTART: the machine fails between jobs, never during one, so no job starts again")
    fun usesUnderRestartFailsBetweenJobs() {
        val m = RuleModel("UsesRestart")
        val machine = ResourceWithQ(m, "Machine")
        machine.interruptRule = InterruptRule.RESTART
        machine.failures(ConstantRV(1.0), ConstantRV(2.0), ResourceFailureBasis.USES)
        m.watch(machine)
        m.plan {
            job("A", machine, 0.0, 5.0)
            job("B", machine, 1.0, 1.0)
        }
        m.simulate(30.0)
        assertEquals(
            listOf("A got@0.0", "Machine fail@5.0", "A done@5.0", "Machine repair@7.0", "B got@7.0", "Machine fail@8.0",
                "B done@8.0", "Machine repair@10.0"),
            m.log
        )
    }
}
