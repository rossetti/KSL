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
import ksl.modeling.guidedpath.spec.GuidedPathNetworkData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  A network must round trip through its specification without loss, so that scenario studies can
 *  generate networks programmatically and so that a layout can be stored beside the model that
 *  uses it. Reconstruction validates exactly as hand-written construction does: a specification
 *  that is wrong fails in the same place, with the same message, however it arrived.
 */
class NetworkSerializationTest {

    @Test
    fun `a network round trips through its data form`() {
        val original = SimpleAgvNetwork.create()
        val rebuilt = GuidedPathNetwork.fromData(original.currentSettings())
        assertEquals(original.name, rebuilt.name)
        assertEquals(original.intersections.map { it.name }, rebuilt.intersections.map { it.name })
        assertEquals(original.links.map { it.name }, rebuilt.links.map { it.name })
        assertEquals(original.zones.map { it.name }, rebuilt.zones.map { it.name })
    }

    @Test
    fun `a network round trips through JSON`() {
        val original = SimpleAgvNetwork.create()
        val rebuilt = GuidedPathNetwork.fromJson(original.settingsToJson())
        assertEquals(original.links.size, rebuilt.links.size)
        assertEquals(original.intersections.size, rebuilt.intersections.size)
    }

    @Test
    fun `link geometry survives the round trip exactly`() {
        val original = SimpleAgvNetwork.create()
        val rebuilt = GuidedPathNetwork.fromJson(original.settingsToJson())
        for (link in original.links) {
            val other = assertNotNull(rebuilt.link(link.name))
            assertEquals(link.length, other.length, link.name)
            assertEquals(link.zoneLength, other.zoneLength, link.name)
            assertEquals(link.numZones, other.numZones, link.name)
            assertEquals(link.type, other.type, link.name)
            assertEquals(link.velocityFactor, other.velocityFactor, link.name)
            assertEquals(link.beginDirection, other.beginDirection, link.name)
        }
    }

    @Test
    fun `intersection properties and layout coordinates survive the round trip`() {
        val original = SimpleAgvNetwork.create()
        val rebuilt = GuidedPathNetwork.fromJson(original.settingsToJson())
        for (i in original.intersections) {
            val other = assertNotNull(rebuilt.intersection(i.name))
            assertEquals(i.length, other.length, i.name)
            assertEquals(i.velocityFactor, other.velocityFactor, i.name)
            assertEquals(i.x, other.x, i.name)
            assertEquals(i.y, other.y, i.name)
        }
    }

    @Test
    fun `station aliases survive the round trip`() {
        val original = SimpleAgvNetwork.create()
        val rebuilt = GuidedPathNetwork.fromJson(original.settingsToJson())
        assertEquals(
            original.stationAliases.mapValues { it.value.name },
            rebuilt.stationAliases.mapValues { it.value.name }
        )
    }

    @Test
    fun `a rebuilt network answers distance identically`() {
        val original = SimpleAgvNetwork.create()
        val rebuilt = GuidedPathNetwork.fromJson(original.settingsToJson())
        for (from in original.intersections) {
            for (to in original.intersections) {
                assertEquals(
                    original.distance(from, to),
                    rebuilt.distance(rebuilt.requireLocation(from.name), rebuilt.requireLocation(to.name)),
                    "${from.name} -> ${to.name}"
                )
            }
        }
    }

    @Test
    fun `an unlisted intersection is created with default properties on reconstruction`() {
        val data = GuidedPathNetworkData(
            name = "N",
            links = listOf(
                ksl.modeling.guidedpath.spec.LinkData.byZoneLength("L1", "A", "B", 12.0, 12.0)
            )
        )
        val net = GuidedPathNetwork.fromData(data)
        val a = assertNotNull(net.intersection("A"))
        assertEquals(0.0, a.length)
        assertEquals(1.0, a.velocityFactor)
        assertTrue(a.x.isNaN())
    }

    @Test
    fun `a specification describing an impossible network fails on reconstruction`() {
        val json = """
            {
              "name": "N",
              "links": [
                {
                  "name": "L1", "fromIntersection": "A", "toIntersection": "B",
                  "length": 50.0, "zoneLength": 12.0, "numZones": 4
                }
              ]
            }
        """.trimIndent()
        val e = assertFailsWith<GuidedPathNetworkException> { GuidedPathNetwork.fromJson(json) }
        assertTrue((e.message ?: "").contains("remainder"), e.message ?: "")
    }

    @Test
    fun `a specification with no links is rejected`() {
        assertFailsWith<GuidedPathNetworkException> {
            GuidedPathNetworkData(name = "N", links = emptyList())
        }
    }

    @Test
    fun `the emitted JSON is human readable and names its elements`() {
        val json = SimpleAgvNetwork.create().settingsToJson()
        assertTrue(json.contains("Link1"), json)
        assertTrue(json.contains("SPUR"), json)
        assertTrue(json.contains(SimpleAgvNetwork.ENTRY_STATION), json)
        assertTrue(json.contains("\n"), "the JSON should be pretty printed")
    }
}
