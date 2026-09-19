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

import ksl.modeling.guidedpath.rules.DistanceIntoZoneControl
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.StartOfZoneControl
import ksl.modeling.guidedpath.rules.ZoneControlRuleIfc
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  When a transporter gives up the zone behind it, to the instant.
 *
 *  This is domain rule R9, and it is the rule most easily implemented approximately rather than
 *  exactly. The three control rules differ only in timing, so a test that watched only where a
 *  transporter ended up would pass for all three no matter which was implemented. What is checked
 *  here is the state of a particular zone at a particular moment: whether a follower could have
 *  moved into it yet.
 *
 *  The straight run used throughout is four zones of twelve feet at ten feet a minute, so a zone
 *  takes one point two minutes to cross and a probe can be placed anywhere inside one.
 */
class ZoneControlRuleTest {

    private class Straight(
        parent: ModelElement,
        rule: ZoneControlRuleIfc,
        lengthInZones: Int = 1,
        placement: TransporterPlacement = TransporterPlacement.At("A")
    ) : ModelElement(parent, "Straight") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Straight")
            .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
            .link("L2", "B", "C", length = 48.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(system, placement, ConstantRV(10.0), lengthInZones, rule, "Cart")

        /** What was true of the guide path at each probed instant. */
        val probes = linkedMapOf<Double, Map<String, ZoneState>>()

        /** How much of itself the cart was accounting for: zones covered, and whether a claim was
         *  outstanding. Recorded at the same instants as [probes]. */
        val coverage = linkedMapOf<Double, Pair<Int, Boolean>>()

        var probeTimes: List<Double> = emptyList()

        /** When to send the cart somewhere else, and where, or null for a single uninterrupted run. */
        var redirectAt: Double? = null
        var redirectTo: String = "B"

        override fun initialize() {
            probes.clear()
            schedule({ _: KSLEvent<Nothing> -> cart.sendTo("C") }, 0.0)
            redirectAt?.let { at ->
                schedule({ _: KSLEvent<Nothing> -> cart.sendTo(redirectTo) }, at)
            }
            for (t in probeTimes) {
                schedule({ _: KSLEvent<Nothing> ->
                    probes[t] = network.zones.associate { it.name to it.state }
                    coverage[t] = cart.coveredZones.size to (cart.claimedZone != null)
                }, t)
            }
        }
    }

    private fun run(
        rule: ZoneControlRuleIfc,
        probeTimes: List<Double>,
        lengthInZones: Int = 1,
        placement: TransporterPlacement = TransporterPlacement.At("A"),
        redirectAt: Double? = null,
        redirectTo: String = "B"
    ): Straight {
        val m = Model("ZoneControl")
        val s = Straight(m, rule, lengthInZones, placement)
        s.probeTimes = probeTimes
        s.redirectAt = redirectAt
        s.redirectTo = redirectTo
        s.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        return s
    }

    // ---- release at the end of the zone ahead -------------------------------------------------

    @Test
    fun `under end control the zone behind is still held while the transporter crosses`() {
        val s = run(EndOfZoneControl(), listOf(0.5, 1.1))
        // The cart left A at time zero and reaches L1.Zone1 at 1.2. Until then A stays held.
        assertEquals(ZoneState.COVERED, s.probes[0.5]!!["A"])
        assertEquals(ZoneState.COVERED, s.probes[1.1]!!["A"])
    }

    @Test
    fun `under end control the zone behind is given up on arrival in the next`() {
        val s = run(EndOfZoneControl(), listOf(1.3))
        assertEquals(ZoneState.FREE, s.probes[1.3]!!["A"])
        assertEquals(ZoneState.COVERED, s.probes[1.3]!!["L1.Zone1"])
    }

    // ---- release at the start of the zone ahead -----------------------------------------------

    @Test
    fun `under start control the zone behind is free at once, before the crossing finishes`() {
        val s = run(StartOfZoneControl(), listOf(0.5, 1.1))
        // This is the whole difference between the two rules: a follower could already be in A.
        assertEquals(ZoneState.FREE, s.probes[0.5]!!["A"])
        assertEquals(ZoneState.FREE, s.probes[1.1]!!["A"])
    }

    @Test
    fun `under start control a one zone transporter briefly covers nothing and holds its claim`() {
        val s = run(StartOfZoneControl(), listOf(0.5))
        // Between zones: the body is in neither, but the zone ahead is reserved, so exactly one
        // zone is denied to everyone else. The invariant checker was running throughout.
        assertEquals(ZoneState.CLAIMED, s.probes[0.5]!!["L1.Zone1"])
        assertEquals(1, s.probes[0.5]!!.values.count { it != ZoneState.FREE })
    }

    // ---- release a stated distance into the zone ahead ----------------------------------------

    @Test
    fun `under distance control the zone behind is held until that distance is covered`() {
        // Six feet into a twelve foot zone at ten feet a minute is 0.6 minutes.
        val s = run(DistanceIntoZoneControl(6.0), listOf(0.3, 0.9))
        assertEquals(ZoneState.COVERED, s.probes[0.3]!!["A"])
        assertEquals(ZoneState.FREE, s.probes[0.9]!!["A"])
    }

    @Test
    fun `a shorter release distance frees the zone behind sooner`() {
        val early = run(DistanceIntoZoneControl(3.0), listOf(0.45))
        val late = run(DistanceIntoZoneControl(9.0), listOf(0.45))
        assertEquals(ZoneState.FREE, early.probes[0.45]!!["A"])
        assertEquals(ZoneState.COVERED, late.probes[0.45]!!["A"])
    }

    @Test
    fun `a release distance longer than the zone ahead releases at that zone's far end`() {
        // Twenty feet into a twelve foot zone cannot happen, so the release lands where the
        // transporter actually leaves the zone: the same instant end control would choose.
        val clamped = run(DistanceIntoZoneControl(20.0), listOf(0.5, 1.1, 1.3))
        val atEnd = run(EndOfZoneControl(), listOf(0.5, 1.1, 1.3))
        for (t in listOf(0.5, 1.1, 1.3)) {
            assertEquals(atEnd.probes[t]!!["A"], clamped.probes[t]!!["A"], "at $t")
        }
    }

    @Test
    fun `a junction with no length releases immediately, since there is nowhere to travel into`() {
        // Every route crosses junctions, so a rule that could not cope with a zone of zero length
        // would be unusable on any network at all.
        val s = run(DistanceIntoZoneControl(6.0), emptyList())
        assertEquals("C", s.cart.frontZone?.name)
    }

    @Test
    fun `a non positive release distance is rejected when the rule is built`() {
        assertFailsWith<IllegalArgumentException> { DistanceIntoZoneControl(0.0) }
        assertFailsWith<IllegalArgumentException> { DistanceIntoZoneControl(-1.0) }
    }

    // ---- driving onto the path, where the guards genuinely differ -----------------------------

    @Test
    fun `a transporter still driving onto the path has no rear zone to give up`() {
        // Placed with its front on the second zone of L1, three zones long: only two are covered,
        // so it is not yet fully on. Under start control it must not release anything, or it would
        // shrink below its own length.
        val s = run(
            StartOfZoneControl(), listOf(0.5),
            lengthInZones = 2, placement = TransporterPlacement.OnZone("L1.Zone2")
        )
        // Two zones covered before it moves; after claiming ahead and releasing one, still two held.
        assertEquals(2, s.cart.heldZones.size)
    }

    @Test
    fun `a multi zone transporter keeps its own length while under way`() {
        val s = run(
            EndOfZoneControl(), listOf(1.3, 3.7, 6.1),
            lengthInZones = 3, placement = TransporterPlacement.OnZone("L1.Zone3")
        )
        for (t in listOf(1.3, 3.7, 6.1)) {
            val occupied = s.probes[t]!!.values.count { it == ZoneState.COVERED }
            assertTrue(occupied <= 3, "at $t the cart covered $occupied zones")
        }
        assertEquals(3, s.cart.coveredZones.size)
    }

    @Test
    fun `a multi zone transporter is never partially on the guide path`() {
        // Open issue OI-6 asked whether a vehicle longer than one zone can be caught covering fewer
        // zones than its length -- the state a progressive entry onto the network would create, and
        // the one `releaseRearAtStart`'s `isFullyOnPath` guard exists to refuse.
        //
        // It cannot, and the reason is that there is no such thing as entering the network. A
        // transporter is placed, and both forms of placement put it on fully: `At` refuses a vehicle
        // longer than one zone outright, and `OnZone` requires its front to be far enough along the
        // link to fit the whole body. From then on a traversal removes one zone and adds one, so the
        // count is only ever the length or one short of it while a claim stands in for the missing
        // one.
        //
        // Asserted here over all three control rules, densely sampled, because the guard reads like
        // a case that happens and is in fact unreachable -- and a reader who assumes the opposite
        // will design for a state the subsystem does not have.
        val probeTimes = (1..80).map { it * 0.15 }
        for (rule in listOf(EndOfZoneControl(), StartOfZoneControl(), DistanceIntoZoneControl(6.0))) {
            val s = run(
                rule, probeTimes, lengthInZones = 3,
                placement = TransporterPlacement.OnZone("L1.Zone3")
            )
            for ((t, seen) in s.coverage) {
                val (covered, claiming) = seen
                assertTrue(
                    covered >= 2,
                    "under $rule at $t the cart covered $covered zones, fewer than its length less one"
                )
                if (!claiming) {
                    assertEquals(
                        3, covered,
                        "under $rule at $t the cart held no claim, so it should cover its whole length"
                    )
                }
            }
        }
    }

    @Test
    fun `all three rules deliver the transporter to the same place at the same time`() {
        // Timing of the release changes who may follow, never how fast the leader travels.
        val ends = run(EndOfZoneControl(), emptyList())
        val starts = run(StartOfZoneControl(), emptyList())
        val distance = run(DistanceIntoZoneControl(6.0), emptyList())
        assertEquals("C", ends.cart.frontZone?.name)
        assertEquals("C", starts.cart.frontZone?.name)
        assertEquals("C", distance.cart.frontZone?.name)
    }

    @Test
    fun `a one zone transporter under start control can be redirected while under way`() {
        // Under release-at-start a transporter as long as one zone gives up the zone behind it the
        // instant it begins to leave, so for the whole of a traversal it covers no zone at all and
        // holds only its claim. Asked where it stands, it answers nowhere -- which is a true answer
        // about a vehicle that is between two places, and is not the same thing as never having
        // been placed. The redirection below is given at 0.6 minutes, half way through the 1.2
        // minute first traversal, which is exactly that state.
        //
        // This is the case Exercise 7.15 runs into continually: three one-zone vehicles under start
        // control, being sent to a staging area and re-tasked on the way.
        val s = run(StartOfZoneControl(), listOf(0.6), redirectAt = 0.6, redirectTo = "B")
        assertEquals(
            0, s.probes[0.6]!!.values.count { it == ZoneState.COVERED },
            "at 0.6 minutes the cart should cover no zone, which is what makes this the case of interest"
        )
        // The redirection takes effect at the next zone boundary, so the cart finishes the zone it
        // was entering and then stops at B rather than carrying on to C.
        assertEquals("B", s.cart.frontZone?.name)
    }

    @Test
    fun `a transporter settles to exactly its own length once it stops`() {
        // Nothing releases after the last zone is entered, so if arrival did not settle the
        // transporter it would stand on more of the path than it covers for the rest of the run.
        for (rule in listOf(EndOfZoneControl(), StartOfZoneControl(), DistanceIntoZoneControl(6.0))) {
            val s = run(rule, emptyList())
            assertEquals(1, s.cart.coveredZones.size, "after $rule")
            assertEquals(null, s.cart.claimedZone, "after $rule")
            assertEquals(1, s.network.zones.count { it.hasHolder }, "after $rule")
        }
    }
}
