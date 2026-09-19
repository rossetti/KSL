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
import ksl.modeling.guidedpath.spec.LinkData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  Geometry and identity of a built network. Verifies domain rules R13 (a link's length is an
 *  integral multiple of its zone length, with positive quantities throughout) and the structural
 *  invariants of the network, its links, and its zones.
 *
 *  Nothing here constructs a Model, an Executive, or a replication, which is the point: the
 *  topology is meant to be buildable and checkable on its own.
 */
class NetworkGeometryTest {

    @Test
    fun `a link divides into the number of zones its length and zone size imply`() {
        val net = GuidedPathNetwork.builder("N")
            .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
            .build()
        val link = assertNotNull(net.link("L1"))
        assertEquals(4, link.numZones)
        assertEquals(4, link.zones.size)
        assertEquals(12.0, link.zoneLength)
        assertEquals(48.0, link.zones.sumOf { it.length })
    }

    @Test
    fun `giving a zone count instead derives the zone size and cannot mismatch`() {
        val net = GuidedPathNetwork.builder("N")
            .linkWithZoneCount("L1", "A", "B", length = 50.0, numZones = 4)
            .build()
        val link = assertNotNull(net.link("L1"))
        assertEquals(12.5, link.zoneLength)
        assertEquals(50.0, link.zones.sumOf { it.length })
    }

    @Test
    fun `zone size belongs to a link so links of different granularity coexist`() {
        val net = SimpleAgvNetwork.create()
        assertEquals(12.0, assertNotNull(net.link("Link1")).zoneLength)
        assertEquals(6.0, assertNotNull(net.link("Link5")).zoneLength)
        assertEquals(1, assertNotNull(net.link("Link5")).numZones)
    }

    @Test
    fun `every zone belongs to exactly one link or intersection and carries a unique name`() {
        val net = SimpleAgvNetwork.create()
        val fromLinks = net.links.flatMap { it.zones }
        val fromIntersections = net.intersections.map { it.zone }
        assertEquals(fromLinks.size + fromIntersections.size, net.zones.size)
        assertEquals(net.zones.size, net.zones.map { it.name }.toSet().size)
        assertEquals(net.zones.size, net.zones.map { it.id }.toSet().size)
    }

    @Test
    fun `a zone identifier is its index in the network zone list`() {
        val net = SimpleAgvNetwork.create()
        for ((index, zone) in net.zones.withIndex()) {
            assertEquals(index, zone.id, "zone ${zone.name}")
        }
    }

    @Test
    fun `link zones run from the beginning intersection toward the ending one`() {
        val net = SimpleAgvNetwork.create()
        val link = assertNotNull(net.link("Link2"))
        assertEquals(6, link.numZones)
        assertTrue(link.zones.first().isFirstOnLink)
        assertTrue(link.zones.last().isLastOnLink)
        assertEquals((1..6).toList(), link.zones.map { it.positionOnLink })
        assertEquals("Link2.Zone1", link.zones.first().name)
    }

    @Test
    fun `an intersection is a place and its zone is the space, related but distinct`() {
        val net = SimpleAgvNetwork.create()
        val i2 = assertNotNull(net.intersection("I2"))
        assertSame(i2, i2.zone.intersection)
        assertEquals("I2", i2.zone.name)
        assertTrue(i2.zone.isDimensionless)
        assertSame(net, i2.spatialModel)
    }

    @Test
    fun `an intersection with a length is still a single exclusive zone`() {
        val net = GuidedPathNetwork.builder("N")
            .intersection("A", length = 5.0, velocityFactor = 0.5)
            .link("L1", "A", "B", length = 24.0, zoneLength = 12.0)
            .build()
        val a = assertNotNull(net.intersection("A"))
        assertEquals(5.0, a.zone.length)
        assertEquals(0.5, a.zone.velocityFactor)
        assertTrue(!a.zone.isDimensionless)
    }

    @Test
    fun `traversal time is the zone length over velocity scaled by the velocity factor`() {
        val net = GuidedPathNetwork.builder("N")
            .link("L1", "A", "B", length = 24.0, zoneLength = 12.0, velocityFactor = 0.5)
            .build()
        val zone = assertNotNull(net.link("L1")).zones.first()
        // Twelve feet at ten feet per minute, halved by the factor, is 2.4 minutes.
        assertEquals(2.4, zone.traversalTime(10.0), 1e-12)
    }

    @Test
    fun `a dimensionless intersection takes no time to cross but is still held`() {
        val net = SimpleAgvNetwork.create()
        val zone = assertNotNull(net.intersection("I2")).zone
        assertEquals(0.0, zone.traversalTime(10.0))
    }

    @Test
    fun `intersections are created on first mention by a link`() {
        val net = GuidedPathNetwork.builder("N")
            .link("L1", "A", "B", length = 12.0, zoneLength = 12.0)
            .link("L2", "B", "C", length = 12.0, zoneLength = 12.0)
            .build()
        assertEquals(listOf("A", "B", "C"), net.intersections.map { it.name })
        assertNull(net.intersection("D"))
    }

    @Test
    fun `incident inbound and outbound links follow the link direction`() {
        val net = SimpleAgvNetwork.create()
        val i2 = assertNotNull(net.intersection("I2"))
        // Link1 arrives, Link2 leaves, Link5 is a bidirectional spur so it does both.
        assertEquals(setOf("Link1", "Link2", "Link5"), i2.incidentLinks.map { it.name }.toSet())
        assertEquals(setOf("Link2", "Link5"), i2.outboundLinks.map { it.name }.toSet())
        assertEquals(setOf("Link1", "Link5"), i2.inboundLinks.map { it.name }.toSet())
    }

    @Test
    fun `a spur terminal is a dead end and the loop intersections are not`() {
        val net = SimpleAgvNetwork.create()
        assertTrue(assertNotNull(net.intersection("I5")).isSpurTerminal)
        assertTrue(assertNotNull(net.intersection("I6")).isSpurTerminal)
        assertTrue(assertNotNull(net.intersection("I7")).isSpurTerminal)
        for (n in listOf("I1", "I2", "I3", "I4")) {
            assertTrue(!assertNotNull(net.intersection(n)).isSpurTerminal, n)
        }
    }

    @Test
    fun `a station alias addresses the intersection it names`() {
        val net = SimpleAgvNetwork.create()
        assertSame(net.intersection("I1"), net.location(SimpleAgvNetwork.ENTRY_STATION))
        assertSame(net.intersection("I5"), net.location(SimpleAgvNetwork.EXIT_STATION))
        // An alias is a name, not a place: it does not add an intersection.
        assertEquals(7, net.intersections.size)
        assertEquals(listOf(SimpleAgvNetwork.ENTRY_STATION), assertNotNull(net.intersection("I1")).aliases)
    }

    @Test
    fun `the book layout has the seven intersections and seven links the text describes`() {
        val net = SimpleAgvNetwork.create()
        assertEquals(7, net.intersections.size)
        assertEquals(7, net.links.size)
        // Four loop zones of twelve feet on Link1, as the text states.
        assertEquals(4, assertNotNull(net.link("Link1")).numZones)
        // Three spurs: the exit and the two home bases.
        assertEquals(3, net.links.count { it.type == LinkType.SPUR })
        // No bidirectional links on the loop, which is what makes the layout deadlock free.
        assertEquals(0, net.links.count { it.type == LinkType.BIDIRECTIONAL })
    }

    @Test
    fun `a link data record round trips through the network unchanged`() {
        val data = LinkData.byZoneLength("L1", "A", "B", 48.0, 12.0, LinkType.BIDIRECTIONAL, 0.75, 90.0)
        val net = GuidedPathNetwork.builder("N").link(data).build()
        val link = assertNotNull(net.link("L1"))
        assertEquals(LinkType.BIDIRECTIONAL, link.type)
        assertEquals(0.75, link.velocityFactor)
        assertEquals(90.0, link.beginDirection)
        assertTrue(link.isTraversableInReverse)
    }
}
