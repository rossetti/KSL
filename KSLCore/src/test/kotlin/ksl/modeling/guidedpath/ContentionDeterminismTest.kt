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
package ksl.modeling.guidedpath

import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.FIFOZoneContentionRule
import ksl.modeling.guidedpath.rules.LoadedFirstZoneContentionRule
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  Contention has to be settled the same way every time, or the model stops being reproducible.
 *
 *  Which of several waiting transporters gets a zone changes the order in which journeys finish,
 *  and everything downstream of that follows. If that choice were left to whatever order a
 *  collection happened to iterate in, the same model could give different answers on different
 *  machines or under different versions of the runtime -- and the difference would be invisible,
 *  because every individual answer would look perfectly reasonable.
 *
 *  The test that matters here is the second one. Running the same model twice proves very little,
 *  since a hash-ordered collection is usually stable within one process. Building the *same* model
 *  with its transporters declared in the opposite order is what would expose a decision that
 *  depended on anything but the stated rule.
 */
class ContentionDeterminismTest {

    /** Four transporters on one file of zones, all wanting to get past the same junction. */
    private class Jam(
        parent: ModelElement,
        reversedDeclaration: Boolean,
        rule: () -> ksl.modeling.guidedpath.rules.ZoneContentionRuleIfc = { FIFOZoneContentionRule() }
    ) : ModelElement(parent, "Jam") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Jam")
            .link("In", "A", "J", length = 60.0, zoneLength = 12.0)
            .link("Out", "J", "B", length = 60.0, zoneLength = 12.0)
            // A separate stopping place for each transporter, further along the same file. A
            // transporter that stops holds its zone for the rest of the run, so a fleet sent to one
            // destination would have its first arrival block everyone behind it -- a real hazard on
            // a guide path, and the reason the text insists on a parking spur per vehicle, but not
            // the thing being measured here.
            .link("B_C", "B", "C", length = 12.0, zoneLength = 12.0)
            .link("C_D", "C", "D", length = 12.0, zoneLength = 12.0)
            .link("D_E", "D", "E", length = 12.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, rule(), name = "Sys")

        private val spots = listOf("In.Zone1", "In.Zone2", "In.Zone3", "In.Zone4")
        private val names = listOf("C1", "C2", "C3", "C4")

        /** The one furthest along goes furthest, so none ever stops in front of another. */
        private val destinations = mapOf("C1" to "B", "C2" to "C", "C3" to "D", "C4" to "E")

        val carts: List<GuidedTransporter> = run {
            val order = if (reversedDeclaration) spots.indices.reversed().toList() else spots.indices.toList()
            val made = LinkedHashMap<String, GuidedTransporter>()
            for (i in order) {
                made[names[i]] = GuidedTransporter(
                    system, TransporterPlacement.OnZone(spots[i]), ConstantRV(10.0), 1, name = names[i]
                )
            }
            names.map { made[it]!! }
        }

        /** Who arrived, and when, in the order they arrived. */
        val arrivals = mutableListOf<Pair<String, Double>>()

        init {
            for (c in carts) c.attachArrivalListener { arrivals.add(it.name to time) }
        }

        override fun initialize() {
            arrivals.clear()
            // Nose to tail, so all four must queue through the junction and each waits for the one
            // ahead of it. Each stops somewhere different, so the queue clears rather than piling up
            // behind whoever arrives first.
            schedule({ _: KSLEvent<Nothing> ->
                carts.forEach { it.sendTo(destinations[it.name.substringAfterLast(":")] ?: "B") }
            }, 0.0)
        }
    }

    private fun run(
        reversedDeclaration: Boolean = false,
        rule: () -> ksl.modeling.guidedpath.rules.ZoneContentionRuleIfc = { FIFOZoneContentionRule() }
    ): Jam {
        val m = Model("Determinism")
        val j = Jam(m, reversedDeclaration, rule)
        j.system.checkInvariants = true
        m.numberOfReplications = 2
        m.lengthOfReplication = 300.0
        m.simulate()
        return j
    }

    @Test
    fun `the same model run twice gives the same arrivals in the same order`() {
        val first = run()
        val second = run()
        assertEquals(first.arrivals, second.arrivals)
    }

    @Test
    fun `declaring the transporters in the opposite order changes nothing`() {
        // The decisive test. A choice made by iteration order rather than by the stated rule would
        // move with the declaration order, and this is what would catch it.
        val forwards = run(reversedDeclaration = false)
        val backwards = run(reversedDeclaration = true)
        assertEquals(forwards.arrivals, backwards.arrivals)
    }

    @Test
    fun `blocking counts and blocked time are identical whichever way the fleet was declared`() {
        val forwards = run(reversedDeclaration = false)
        val backwards = run(reversedDeclaration = true)
        for (name in listOf("C1", "C2", "C3", "C4")) {
            val a = forwards.carts.first { it.name.endsWith(name) }
            val b = backwards.carts.first { it.name.endsWith(name) }
            assertEquals(a.numTimesBlocked.value, b.numTimesBlocked.value, "blocking count for $name")
            assertEquals(
                a.fracTimeBlocked.withinReplicationAverage,
                b.fracTimeBlocked.withinReplicationAverage,
                1e-12,
                "blocked time for $name"
            )
        }
    }

    @Test
    fun `waiting is settled first come first served by default`() {
        val j = run()
        // Nose to tail on a one-way file, so they can only arrive in the order they were queued:
        // the one furthest along first.
        assertEquals(listOf("C4", "C3", "C2", "C1"), j.arrivals.map { it.first })
    }

    @Test
    fun `the rule in force is the one the system was given`() {
        val j = run(rule = { LoadedFirstZoneContentionRule() })
        assertTrue(j.system.zoneContentionRule is LoadedFirstZoneContentionRule)
    }

    @Test
    fun `a rule may not choose a transporter that is not waiting`() {
        // A rule is user code, so a choice from outside the list it was given is a defect in the
        // extension. Letting it through would hand a zone to a transporter that never asked for it.
        val m = Model("BadRule")
        val stranger = arrayOfNulls<GuidedTransporter>(1)
        val j = Jam(m, false) {
            ksl.modeling.guidedpath.rules.ZoneContentionRuleIfc { _, _ -> stranger[0]!! }
        }
        stranger[0] = j.carts.last()
        j.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 300.0
        val e = kotlin.test.assertFailsWith<IllegalStateException> { m.simulate() }
        assertTrue((e.message ?: "").contains("not waiting"), e.message ?: "")
    }

    // ---- the priority that orders a wake-up against everything else at the same instant --------

    @Test
    fun `a woken claim is settled before other transporters finish crossing zones`() {
        // Smaller values are higher priority. A wake-up that ran after other arrivals at the same
        // instant could see a zone taken in between, and which transporter got it would then depend
        // on the order events happened to be scheduled in rather than on the contention rule.
        assertTrue(ProcessModel.ZONE_CLAIM_PRIORITY < ProcessModel.MOVE_PRIORITY)
        assertTrue(ProcessModel.ZONE_CLAIM_PRIORITY > ProcessModel.RESUME_PRIORITY)
    }

    @Test
    fun `every transporter that is held up eventually gets through`() {
        val j = run()
        assertEquals(4, j.arrivals.size)
        assertTrue(j.system.blockedTransporters.isEmpty())
        // Three of the four had to wait for the one ahead.
        assertTrue(j.carts.count { it.numTimesBlocked.value > 0 } >= 3)
    }

    @Test
    fun `arrivals are strictly ordered in time, so no two got the same zone at once`() {
        val j = run()
        val times = j.arrivals.map { it.second }
        assertEquals(times.sorted(), times)
        assertNotEquals(times.first(), times.last())
        assertSame(j.system.network, j.network)
    }
}
