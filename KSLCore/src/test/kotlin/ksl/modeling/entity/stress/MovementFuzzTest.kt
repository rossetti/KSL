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

package ksl.modeling.entity.stress

import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.ResourceInvariants
import ksl.modeling.fleet.AgvTestNetworks
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.ConsolidatingPolicy
import ksl.modeling.fleet.policies.ContractNetAssignmentPolicy
import ksl.modeling.fleet.policies.LeastUsedVehiclePolicy
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.policies.PullFromBoardPolicy
import ksl.modeling.fleet.policies.ReassigningPolicy
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertTrue

/**
 *  Randomized runs of conveyors, an AGV fleet and guided transporters, with terminations of the entities
 *  using them and stops, failures or shifts of the equipment. Each run must end without an exception and,
 *  near the end of each replication, with every entity done or terminated and the equipment empty (see the
 *  `snapshot` of `ConveyorFuzzLines`, `FleetFuzzYard` and `GuidedFuzzFloor`). The library's resource
 *  invariant checks are switched on, since a guided transporter is a resource.
 */
class MovementFuzzTest {

    private var myInvariantsWereEnabled = false

    @BeforeEach
    fun enableInvariants() {
        myInvariantsWereEnabled = ResourceInvariants.enabled
        ResourceInvariants.enabled = true
    }

    @AfterEach
    fun restoreInvariants() {
        ResourceInvariants.enabled = myInvariantsWereEnabled
    }

    private fun describe(label: String, bad: List<String>): String =
        "$label: ${bad.size} bad outcomes" + (if (bad.size > 12) " (first 12 shown)" else "") + "\n" +
                bad.take(12).joinToString("\n")

    // ------------------------------------------------------------------------------------------------
    // conveyors
    // ------------------------------------------------------------------------------------------------

    private class ConveyorOutcome(val report: String?, val error: Throwable?, val log: List<String>, val plan: List<String>)

    private fun conveyorRun(seed: Long, t1: Conveyor.Type, t2: Conveyor.Type, kills: Boolean, stops: Boolean): ConveyorOutcome {
        val rnd = Random(seed)
        val model = Model("ConvFuzz${System.nanoTime()}", autoCSVReports = false)
        val lines = ConveyorFuzzLines(model, t1, t2)
        val plan = mutableListOf<String>()
        val n = 4 + rnd.nextInt(5)
        val specs = (1..n).map {
            val mode = rnd.nextInt(7)
            val cells = 1 + rnd.nextInt(2)
            val arrive = rnd.nextInt(24) * 0.5
            val d = listOf(0.0, 0.0, 0.5, 1.0, 2.5)[rnd.nextInt(5)]
            plan.add("E$it arrive=$arrive cells=$cells mode=$mode d=$d")
            listOf(it.toDouble(), arrive, cells.toDouble(), mode.toDouble(), d)
        }
        val killSpecs = if (kills) (1..(1 + rnd.nextInt(3))).map {
            val who = 1 + rnd.nextInt(n)
            val t = rnd.nextInt(60) * 0.5
            val pr = listOf(1, KSLEvent.DEFAULT_PRIORITY, 5, 9, 15, 25, 40)[rnd.nextInt(7)]
            plan.add("kill E$who at $t pr=$pr")
            Triple(who, t, pr)
        } else emptyList()
        val stopSpecs = if (stops) (1..(1 + rnd.nextInt(2))).map {
            val which = rnd.nextInt(2)
            val t = rnd.nextInt(50) * 0.5
            val len = listOf(0.0, 0.5, 1.0, 3.0)[rnd.nextInt(4)]
            plan.add("stop C${which + 1} at $t for $len")
            Triple(which, t, len)
        } else emptyList()
        lines.setups.add {
            for (s in specs) item("E${s[0].toInt()}", s[1], s[2].toInt(), s[3].toInt(), s[4])
            for ((who, t, pr) in killSpecs) kill("E$who", t, pr)
            for ((which, t, len) in stopSpecs) {
                val c = if (which == 0) c1 else c2
                at(t) { c.stopConveyor() }
                at(t + len, priority = KSLEvent.DEFAULT_PRIORITY + 1) { c.startConveyor() }
            }
            at(299.0) { endReport = snapshot() }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 300.0
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        return ConveyorOutcome(lines.endReport, error, lines.log.toList(), plan)
    }

    /** Runs [seeds] for each pairing of accumulating and non-accumulating conveyors; returns the bad outcomes. */
    private fun conveyorFuzz(kills: Boolean, stops: Boolean, seeds: LongRange): List<String> {
        val bad = mutableListOf<String>()
        val types = listOf(Conveyor.Type.ACCUMULATING, Conveyor.Type.NON_ACCUMULATING)
        for (t1 in types) for (t2 in types) for (seed in seeds) {
            val o = conveyorRun(seed, t1, t2, kills, stops)
            if (o.error != null || o.report != null) {
                bad.add(
                    "seed=$seed C1=$t1 C2=$t2 error=${o.error?.toString()?.take(400)} report=${o.report}\n" +
                            "   plan=${o.plan}\n   log=${o.log}"
                )
            }
        }
        return bad
    }

    @Test
    @DisplayName("Random items on two conveyors all finish and leave the conveyors empty")
    fun conveyorItemsFinish() {
        val bad = conveyorFuzz(kills = false, stops = false, seeds = 1L..150L)
        assertTrue(bad.isEmpty(), describe("conveyor runs", bad))
    }

    @Test
    @DisplayName("Random items on two conveyors that stop and restart all finish and leave the conveyors empty")
    fun conveyorItemsFinishWithStops() {
        val bad = conveyorFuzz(kills = false, stops = true, seeds = 1L..150L)
        assertTrue(bad.isEmpty(), describe("conveyor stop and start runs", bad))
    }

    @Test
    @DisplayName("Terminating random items on two conveyors leaves the conveyors empty")
    fun conveyorItemsTerminated() {
        val bad = conveyorFuzz(kills = true, stops = false, seeds = 1L..300L)
        assertTrue(bad.isEmpty(), describe("conveyor termination runs", bad))
    }

    @Test
    @DisplayName("Terminating random items on conveyors that stop and restart leaves the conveyors empty")
    fun conveyorItemsTerminatedWithStops() {
        val bad = conveyorFuzz(kills = true, stops = true, seeds = 1L..300L)
        assertTrue(bad.isEmpty(), describe("conveyor termination and stop runs", bad))
    }

    // ------------------------------------------------------------------------------------------------
    // AGV fleet
    // ------------------------------------------------------------------------------------------------

    private class YardOutcome(val error: Throwable?, val reports: List<String?>, val logs: List<List<String>>)

    private fun runYard(
        policy: AssignmentPolicyIfc, capacity: Int, failing: Boolean, reps: Int, length: Double = 600.0,
        setup: FleetFuzzYard.() -> Unit
    ): YardOutcome {
        val model = Model("Yard${System.nanoTime()}", autoCSVReports = false)
        val yard = FleetFuzzYard(model, policy, capacity, failing)
        val reports = mutableListOf<String?>()
        val logs = mutableListOf<List<String>>()
        yard.setups.add(setup)
        yard.setups.add { at(length - 1.0) { reports.add(snapshot()); logs.add(log.toList()) } }
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        if (logs.size < reps) logs.add(yard.log.toList())
        return YardOutcome(error, reports, logs)
    }

    private fun policyOf(i: Int): Pair<String, () -> AssignmentPolicyIfc> = when (i) {
        0 -> "Nearest" to { NearestVehiclePolicy() }
        1 -> "Pull" to { PullFromBoardPolicy() }
        2 -> "LeastUsed" to { LeastUsedVehiclePolicy() }
        3 -> "CNet0" to { ContractNetAssignmentPolicy(0.0) }
        4 -> "CNet1.5" to { ContractNetAssignmentPolicy(1.5) }
        5 -> "Batched1" to { BatchedAssignmentPolicy(1.0) }
        6 -> "Consolidating" to { ConsolidatingPolicy(NearestVehiclePolicy()) }
        else -> "Reassigning" to { ReassigningPolicy(50.0) }
    }

    /** Runs [seeds] under each of eight assignment policies, two replications each; returns the bad outcomes. */
    private fun fleetFuzz(kills: Boolean, failing: Boolean, seeds: LongRange): List<String> {
        val bad = mutableListOf<String>()
        val stations = listOf(AgvTestNetworks.NEAR, AgvTestNetworks.FAR, AgvTestNetworks.ALT_DROP)
        for (pi in 0..7) for (seed in seeds) {
            val (pname, make) = policyOf(pi)
            val rnd = Random(seed * 31 + pi)
            val capacity = if (pi == 6) 2 else 1 + rnd.nextInt(2)
            val plan = mutableListOf<String>()
            val n = 3 + rnd.nextInt(4)
            data class L(val tag: String, val from: Double, val o: String, val d: String, val ld: Double, val ul: Double, val split: Double?)
            val loads = (1..n).map {
                val o = stations[rnd.nextInt(3)]
                var d = stations[rnd.nextInt(3)]
                if (d == o) d = stations[(stations.indexOf(o) + 1) % 3]
                val l = L(
                    "L$it", rnd.nextInt(30).toDouble(), o, d, listOf(0.0, 0.0, 2.0)[rnd.nextInt(3)],
                    listOf(0.0, 0.0, 2.0)[rnd.nextInt(3)], listOf<Double?>(null, null, 3.0, 25.0)[rnd.nextInt(4)]
                )
                plan.add(l.toString())
                l
            }
            val killSpecs = if (kills) (1..(1 + rnd.nextInt(2))).map {
                val who = 1 + rnd.nextInt(n)
                val t = rnd.nextInt(160) * 0.5
                val pr = listOf(1, KSLEvent.DEFAULT_PRIORITY, 9, 40)[rnd.nextInt(4)]
                plan.add("kill L$who at $t pr=$pr")
                Triple(who, t, pr)
            } else emptyList()
            val o = runYard(policy = make(), capacity = capacity, failing = failing, reps = 2) {
                for (l in loads) load(l.tag, l.from, l.o, l.d, l.ld, l.ul, l.split)
                for ((who, t, pr) in killSpecs) kill("L$who", t, pr)
            }
            if (o.error != null || o.reports.any { it != null }) {
                bad.add(
                    "seed=$seed policy=$pname cap=$capacity error=${o.error?.toString()?.take(500)} reports=${o.reports}\n" +
                            "   plan=$plan\n   log=${o.logs}"
                )
            }
        }
        return bad
    }

    @Test
    @DisplayName("Random loads carried by an AGV fleet are all delivered under every assignment policy")
    fun fleetLoadsDelivered() {
        val bad = fleetFuzz(kills = false, failing = false, seeds = 1L..40L)
        assertTrue(bad.isEmpty(), describe("fleet runs", bad))
    }

    @Test
    @DisplayName("Terminating random loads of an AGV fleet leaves no task, vehicle or hold queue behind")
    fun fleetLoadsTerminated() {
        val bad = fleetFuzz(kills = true, failing = false, seeds = 1L..80L)
        assertTrue(bad.isEmpty(), describe("fleet termination runs", bad))
    }

    @Test
    @DisplayName("Random loads carried by an AGV fleet with a failing vehicle are all delivered")
    fun fleetLoadsDeliveredWithFailures() {
        val bad = fleetFuzz(kills = false, failing = true, seeds = 1L..40L)
        assertTrue(bad.isEmpty(), describe("fleet runs with a failing vehicle", bad))
    }

    @Test
    @DisplayName("Terminating random loads of an AGV fleet with a failing vehicle leaves nothing behind")
    fun fleetLoadsTerminatedWithFailures() {
        val bad = fleetFuzz(kills = true, failing = true, seeds = 1L..80L)
        assertTrue(bad.isEmpty(), describe("fleet termination runs with a failing vehicle", bad))
    }

    // ------------------------------------------------------------------------------------------------
    // guided transporters
    // ------------------------------------------------------------------------------------------------

    /** Runs [seeds], two replications each; returns the bad outcomes. */
    private fun guidedFuzz(kills: Boolean, outages: Boolean, seeds: LongRange): List<String> {
        val bad = mutableListOf<String>()
        val stations = listOf(AgvTestNetworks.NEAR, AgvTestNetworks.FAR, AgvTestNetworks.ALT_DROP)
        for (seed in seeds) {
            val rnd = Random(seed)
            val plan = mutableListOf<String>()
            val n = 3 + rnd.nextInt(4)
            val parts = (1..n).map {
                val o = stations[rnd.nextInt(3)]
                var d = stations[rnd.nextInt(3)]
                if (d == o && rnd.nextBoolean()) d = stations[(stations.indexOf(o) + 1) % 3]
                val spec = listOf("P$it", rnd.nextInt(30).toDouble(), o, d, listOf(0.0, 0.0, 2.0)[rnd.nextInt(3)], listOf(0.0, 0.0, 2.0)[rnd.nextInt(3)])
                plan.add(spec.toString())
                spec
            }
            val killSpecs = if (kills) (1..(1 + rnd.nextInt(2))).map {
                val who = 1 + rnd.nextInt(n)
                val t = rnd.nextInt(160) * 0.5
                val pr = listOf(1, KSLEvent.DEFAULT_PRIORITY, 9, 40)[rnd.nextInt(4)]
                plan.add("kill P$who at $t pr=$pr")
                Triple(who, t, pr)
            } else emptyList()
            val outageSpecs = if (outages) (1..(1 + rnd.nextInt(2))).map {
                val which = rnd.nextInt(2)
                val kind = rnd.nextInt(2)
                val t = rnd.nextInt(120) * 0.5
                val len = listOf(0.0, 1.0, 5.0, 20.0)[rnd.nextInt(4)]
                plan.add("${if (kind == 0) "fail" else "offShift"} C${which + 1} at $t for $len")
                listOf(which.toDouble(), kind.toDouble(), t, len)
            } else emptyList()
            val model = Model("Floor${System.nanoTime()}", autoCSVReports = false)
            val floor = GuidedFuzzFloor(model)
            val reports = mutableListOf<String?>()
            val logs = mutableListOf<List<String>>()
            floor.setups.add {
                for (s in parts) part(s[0] as String, s[1] as Double, s[2] as String, s[3] as String, s[4] as Double, s[5] as Double)
                for ((who, t, pr) in killSpecs) kill("P$who", t, pr)
                for (s in outageSpecs) {
                    val c = carts[s[0].toInt()]
                    if (s[1] == 0.0) {
                        at(s[2]) { if (!c.isFailed) c.fail() }
                        at(s[2] + s[3], priority = KSLEvent.DEFAULT_PRIORITY + 1) { if (c.isFailed) c.repair() }
                    } else {
                        at(s[2]) { c.goOffShift() }
                        at(s[2] + s[3], priority = KSLEvent.DEFAULT_PRIORITY + 1) { c.goOnShift() }
                    }
                }
                at(599.0) { reports.add(snapshot()); logs.add(log.toList()) }
            }
            model.numberOfReplications = 2
            model.lengthOfReplication = 600.0
            val error = try {
                model.simulate(); null
            } catch (e: Throwable) {
                e
            }
            if (error != null || reports.any { it != null }) {
                bad.add(
                    "seed=$seed error=${error?.toString()?.take(500)} reports=$reports\n" +
                            "   plan=$plan\n   log=${if (logs.isEmpty()) listOf(floor.log.toList()) else logs}"
                )
            }
        }
        return bad
    }

    @Test
    @DisplayName("Random parts carried by pooled guided transporters are all delivered")
    fun guidedPartsDelivered() {
        val bad = guidedFuzz(kills = false, outages = false, seeds = 1L..100L)
        assertTrue(bad.isEmpty(), describe("guided transporter runs", bad))
    }

    @Test
    @DisplayName("Random parts are all delivered when guided transporters fail or go off shift")
    fun guidedPartsDeliveredWithOutages() {
        val bad = guidedFuzz(kills = false, outages = true, seeds = 1L..200L)
        assertTrue(bad.isEmpty(), describe("guided transporter outage runs", bad))
    }

    @Test
    @DisplayName("Terminating random parts leaves the guided transporters idle and the pool queue empty")
    fun guidedPartsTerminated() {
        val bad = guidedFuzz(kills = true, outages = false, seeds = 1L..300L)
        assertTrue(bad.isEmpty(), describe("guided transporter termination runs", bad))
    }

    @Test
    @DisplayName("Terminating random parts during transporter outages leaves the transporters idle and restored")
    fun guidedPartsTerminatedWithOutages() {
        val bad = guidedFuzz(kills = true, outages = true, seeds = 1L..300L)
        assertTrue(bad.isEmpty(), describe("guided transporter termination and outage runs", bad))
    }
}
