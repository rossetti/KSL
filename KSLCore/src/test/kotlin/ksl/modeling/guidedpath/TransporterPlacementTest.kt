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

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.guidedpath.exceptions.GuidedPathNetworkException
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Where transporters stand when a replication begins, and what happens when that cannot be
 *  arranged.
 *
 *  Placement matters more on a guide path than in an ordinary resource pool, because a stationary
 *  transporter occupies space that others may need. It is declared rather than remembered and
 *  re-applied every replication, so a run that ends with the fleet scattered cannot bias the next
 *  one. That is domain rule R11, and it is checked here alongside the ways a placement can be
 *  impossible.
 */
class TransporterPlacementTest {

    private class Fleet(
        parent: ModelElement,
        val placements: List<Pair<String, TransporterPlacement>>,
        val lengths: List<Int> = placements.map { 1 }
    ) : ModelElement(parent, "Fleet") {
        val system = GuidedPathTransportSystem(this, SimpleAgvNetwork.create(), name = "Sys")
        val carts = placements.mapIndexed { i, (name, placement) ->
            GuidedTransporter(system, placement, ConstantRV(10.0), lengths[i], name = name)
        }
    }

    private fun run(
        placements: List<Pair<String, TransporterPlacement>>,
        lengths: List<Int> = placements.map { 1 },
        replications: Int = 1,
        drive: ((Fleet) -> Unit)? = null
    ): Fleet {
        val m = Model("Placement")
        val fleet = Fleet(m, placements, lengths)
        fleet.system.checkInvariants = true
        drive?.invoke(fleet)
        m.numberOfReplications = replications
        m.lengthOfReplication = 200.0
        m.simulate()
        return fleet
    }

    @Test
    fun `a transporter placed at a junction covers that junction's zone`() {
        val fleet = run(listOf("C1" to TransporterPlacement.At("I6")))
        assertEquals(listOf("I6"), fleet.carts[0].coveredZones.map { it.name })
        assertEquals("I6", fleet.carts[0].currentLocation.name)
    }

    @Test
    fun `a station alias may be used to place a transporter`() {
        val fleet = run(listOf("C1" to TransporterPlacement.At(SimpleAgvNetwork.ENTRY_STATION)))
        assertEquals(listOf("I1"), fleet.carts[0].coveredZones.map { it.name })
    }

    @Test
    fun `a transporter placed on a link covers backwards from the zone named`() {
        val fleet = run(
            listOf("C1" to TransporterPlacement.OnZone("Link2.Zone4")),
            lengths = listOf(3)
        )
        assertEquals(
            listOf("Link2.Zone2", "Link2.Zone3", "Link2.Zone4"),
            fleet.carts[0].coveredZones.map { it.name }
        )
    }

    @Test
    fun `the book layout parks each cart on its own home spur, clear of the loop`() {
        val fleet = run(
            listOf(
                "AGV1" to TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME),
                "AGV2" to TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME)
            )
        )
        assertEquals(listOf("I6"), fleet.carts[0].coveredZones.map { it.name })
        assertEquals(listOf("I7"), fleet.carts[1].coveredZones.map { it.name })
        // Nothing of the main loop is held, which is the point of a home spur.
        val loop = listOf("I1", "I2", "I3", "I4")
        for (n in loop) {
            assertTrue(fleet.system.network.intersection(n)!!.zone.isAvailable, n)
        }
    }

    // ---- placements that cannot be arranged ---------------------------------------------------

    @Test
    fun `two transporters cannot be placed in the same zone`() {
        val e = assertFailsWith<GuidedPathNetworkException> {
            run(
                listOf(
                    "C1" to TransporterPlacement.At("I6"),
                    "C2" to TransporterPlacement.At("I6")
                )
            )
        }
        val m = e.message ?: ""
        assertTrue(m.contains("C1"), m)
        assertTrue(m.contains("C2"), m)
        assertTrue(m.contains("I6"), m)
    }

    @Test
    fun `overlapping multi zone placements are refused`() {
        val e = assertFailsWith<GuidedPathNetworkException> {
            run(
                listOf(
                    "C1" to TransporterPlacement.OnZone("Link2.Zone3"),
                    "C2" to TransporterPlacement.OnZone("Link2.Zone4")
                ),
                lengths = listOf(2, 2)
            )
        }
        assertTrue((e.message ?: "").contains("Link2.Zone3"), e.message ?: "")
    }

    @Test
    fun `a transporter longer than one zone cannot stand at a junction`() {
        val e = assertFailsWith<GuidedPathNetworkException> {
            run(listOf("C1" to TransporterPlacement.At("I6")), lengths = listOf(2))
        }
        assertTrue((e.message ?: "").contains("single zone"), e.message ?: "")
    }

    @Test
    fun `a transporter longer than the link it is placed on is refused`() {
        val e = assertFailsWith<GuidedPathNetworkException> {
            run(listOf("C1" to TransporterPlacement.OnZone("Link1.Zone4")), lengths = listOf(9))
        }
        val m = e.message ?: ""
        assertTrue(m.contains("Link1"), m)
        assertTrue(m.contains("9 zones"), m)
    }

    @Test
    fun `a transporter whose rear would hang off the start of a link is refused`() {
        val e = assertFailsWith<GuidedPathNetworkException> {
            run(listOf("C1" to TransporterPlacement.OnZone("Link1.Zone2")), lengths = listOf(3))
        }
        assertTrue((e.message ?: "").contains("hang off"), e.message ?: "")
    }

    @Test
    fun `a placement naming somewhere the network does not have is refused`() {
        val e = assertFailsWith<GuidedPathNetworkException> {
            run(listOf("C1" to TransporterPlacement.At("Nowhere")))
        }
        assertTrue((e.message ?: "").contains("Nowhere"), e.message ?: "")
    }

    @Test
    fun `a placement naming a zone the network does not have is refused`() {
        val e = assertFailsWith<GuidedPathNetworkException> {
            run(listOf("C1" to TransporterPlacement.OnZone("Link9.Zone1")))
        }
        assertTrue((e.message ?: "").contains("Link9.Zone1"), e.message ?: "")
    }

    // ---- replication independence, domain rule R11 --------------------------------------------

    @Test
    fun `every replication starts with the fleet back where it was declared`() {
        // The reset happens when a replication begins, so it has to be observed there. Looking
        // after the run would only show where the last replication happened to leave things.
        val seen = mutableListOf<Pair<Int, List<String>>>()
        val m = Model("Reset")
        val fleet = Fleet(
            m,
            listOf(
                "AGV1" to TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME),
                "AGV2" to TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME)
            )
        )
        fleet.system.checkInvariants = true
        object : ModelElement(fleet, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> ->
                    seen.add(model.currentReplicationNumber to fleet.carts.map { it.frontZone!!.name })
                }, 0.5)
                // Drive both carts well away from home, so a replication that failed to reset
                // would start from somewhere else entirely.
                schedule({ _: KSLEvent<Nothing> -> fleet.carts[0].sendTo("I4") }, 1.0)
                schedule({ _: KSLEvent<Nothing> -> fleet.carts[1].sendTo("I1") }, 1.0)
            }
        }
        m.numberOfReplications = 4
        m.lengthOfReplication = 200.0
        m.simulate()

        assertEquals(4, seen.size)
        for ((replication, positions) in seen) {
            assertEquals(listOf("I6", "I7"), positions, "at the start of replication $replication")
        }
    }

    @Test
    fun `no zone stays held across the boundary between replications`() {
        val heldAtStart = mutableListOf<Int>()
        val m = Model("ResetZones")
        val fleet = Fleet(m, listOf("C1" to TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME)))
        fleet.system.checkInvariants = true
        object : ModelElement(fleet, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> ->
                    heldAtStart.add(fleet.system.network.zones.count { it.hasHolder })
                }, 0.5)
                schedule({ _: KSLEvent<Nothing> -> fleet.carts[0].sendTo("I4") }, 1.0)
            }
        }
        m.numberOfReplications = 3
        m.lengthOfReplication = 200.0
        m.simulate()

        // One zone held at the start of every replication: the cart's own, and nothing left over
        // from a cart that was somewhere else when the previous replication ended.
        assertEquals(listOf(1, 1, 1), heldAtStart)
    }

    @Test
    fun `the initial placement cannot be changed while the model is running`() {
        val m = Model("Frozen")
        val fleet = Fleet(m, listOf("C1" to TransporterPlacement.At("I6")))
        // Before running, changing it is fine.
        fleet.carts[0].initialPlacement = TransporterPlacement.At("I7")
        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        m.simulate()
        assertEquals("I7", fleet.carts[0].frontZone?.name)
    }

    @Test
    fun `a second system cannot attach to a network another is already running`() {
        val m = Model("TwoSystems")
        val net = SimpleAgvNetwork.create()
        val holder = object : ModelElement(m, "Holder") {}
        GuidedPathTransportSystem(holder, net, name = "First")
        val e = assertFailsWith<GuidedPathNetworkException> {
            GuidedPathTransportSystem(holder, net, name = "Second")
        }
        val msg = e.message ?: ""
        assertTrue(msg.contains("First"), msg)
        assertTrue(msg.contains("one network per model"), msg)
    }
}
