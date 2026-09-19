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

import ksl.modeling.guidedpath.exceptions.GuidedPathNetworkException
import ksl.modeling.guidedpath.spec.IntersectionData
import ksl.modeling.guidedpath.spec.LinkData
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  A malformed network must be rejected where it is described, not where it is first traversed,
 *  and the message must name the offending element and the values that make it invalid. A modeler
 *  who reads only the message should be able to act on it.
 *
 *  Every case below asserts on message content rather than merely on the exception type, because
 *  the message is the contract: a correctly-typed exception carrying an unhelpful message would
 *  pass a type-only test while leaving the modeler no better off than an arithmetic failure part
 *  way through a run, which is the outcome this validation exists to prevent.
 */
class NetworkValidationTest {

    private fun assertMessageNames(block: () -> Unit, vararg fragments: String) {
        val e = assertFailsWith<GuidedPathNetworkException> { block() }
        val m = e.message ?: ""
        for (f in fragments) {
            assertTrue(m.contains(f), "message did not contain \"$f\": $m")
        }
    }

    // ---- link geometry, domain rule R13 -------------------------------------------------------

    @Test
    fun `a length that is not a whole number of zones is rejected with its remainder`() {
        assertMessageNames(
            { LinkData("L1", "A", "B", length = 50.0, zoneLength = 12.0, numZones = 4) },
            "L1", "50.0", "12.0", "remainder"
        )
    }

    @Test
    fun `a zero length link is rejected`() {
        assertMessageNames(
            { LinkData("L1", "A", "B", length = 0.0, zoneLength = 12.0, numZones = 1) },
            "L1", "length", "> 0.0"
        )
    }

    @Test
    fun `a negative length link is rejected`() {
        assertMessageNames(
            { LinkData("L1", "A", "B", length = -48.0, zoneLength = 12.0, numZones = 4) },
            "L1", "length"
        )
    }

    @Test
    fun `a zero zone length is rejected`() {
        assertMessageNames(
            { LinkData("L1", "A", "B", length = 48.0, zoneLength = 0.0, numZones = 4) },
            "L1", "zoneLength"
        )
    }

    @Test
    fun `a zero zone count is rejected`() {
        assertMessageNames(
            { LinkData("L1", "A", "B", length = 48.0, zoneLength = 12.0, numZones = 0) },
            "L1", "must be >= 1", "0"
        )
    }

    @Test
    fun `a zero velocity factor is rejected`() {
        assertMessageNames(
            { LinkData("L1", "A", "B", length = 48.0, zoneLength = 12.0, numZones = 4, velocityFactor = 0.0) },
            "L1", "velocityFactor"
        )
    }

    @Test
    fun `a negative velocity factor is rejected`() {
        assertMessageNames(
            { LinkData("L1", "A", "B", length = 48.0, zoneLength = 12.0, numZones = 4, velocityFactor = -1.0) },
            "L1", "velocityFactor"
        )
    }

    @Test
    fun `a link from an intersection to itself is rejected`() {
        assertMessageNames(
            { LinkData("L1", "A", "A", length = 48.0, zoneLength = 12.0, numZones = 4) },
            "L1", "A", "distinct"
        )
    }

    @Test
    fun `a blank link name is rejected`() {
        assertMessageNames(
            { LinkData("", "A", "B", length = 48.0, zoneLength = 12.0, numZones = 4) },
            "must not be blank"
        )
    }

    @Test
    fun `a length shorter than one zone is rejected rather than rounded to nothing`() {
        assertMessageNames(
            { LinkData.byZoneLength("L1", "A", "B", length = 4.0, zoneLength = 12.0) },
            "L1", "must be >= 1"
        )
    }

    // ---- intersection properties --------------------------------------------------------------

    @Test
    fun `a negative intersection length is rejected`() {
        assertMessageNames({ IntersectionData("A", length = -1.0) }, "A", "length")
    }

    @Test
    fun `a zero intersection velocity factor is rejected`() {
        assertMessageNames({ IntersectionData("A", velocityFactor = 0.0) }, "A", "velocityFactor")
    }

    @Test
    fun `a blank intersection name is rejected`() {
        assertMessageNames({ IntersectionData("") }, "must not be blank")
    }

    // ---- network topology ---------------------------------------------------------------------

    @Test
    fun `a duplicate link name is rejected because names identify links everywhere`() {
        assertMessageNames({
            GuidedPathNetwork.builder("N")
                .link("L1", "A", "B", length = 12.0, zoneLength = 12.0)
                .link("L1", "B", "C", length = 12.0, zoneLength = 12.0)
                .build()
        }, "Duplicate link name", "L1")
    }

    @Test
    fun `a duplicate intersection declaration is rejected`() {
        assertMessageNames({
            GuidedPathNetwork.builder("N")
                .intersection("A")
                .intersection("A")
                .build()
        }, "Duplicate intersection name", "A")
    }

    @Test
    fun `a spur that does not end at a dead end is rejected naming the offending links`() {
        assertMessageNames({
            GuidedPathNetwork.builder("N")
                .link("L1", "A", "B", length = 12.0, zoneLength = 12.0, type = LinkType.SPUR)
                .link("L2", "B", "C", length = 12.0, zoneLength = 12.0)
                .build()
        }, "L1", "SPUR", "B", "degree 2", "L2")
    }

    @Test
    fun `a station alias colliding with an intersection name is rejected`() {
        assertMessageNames({
            GuidedPathNetwork.builder("N")
                .link("L1", "A", "B", length = 12.0, zoneLength = 12.0)
                .station("A", "B")
                .build()
        }, "A", "collides")
    }

    @Test
    fun `a duplicate station alias is rejected`() {
        assertMessageNames({
            GuidedPathNetwork.builder("N")
                .link("L1", "A", "B", length = 12.0, zoneLength = 12.0)
                .station("Dock", "A")
                .station("Dock", "B")
                .build()
        }, "Duplicate station alias", "Dock")
    }

    @Test
    fun `a station alias naming an intersection that does not exist is rejected`() {
        assertMessageNames({
            GuidedPathNetwork.builder("N")
                .link("L1", "A", "B", length = 12.0, zoneLength = 12.0)
                .station("Dock", "Z")
                .build()
        }, "Dock", "Z", "may only name intersections that exist")
    }

    @Test
    fun `a network with no links is rejected`() {
        assertMessageNames(
            { GuidedPathNetwork.builder("N").intersection("A").intersection("B").build() },
            "at least one link"
        )
    }

    @Test
    fun `a blank network name is rejected`() {
        assertFailsWith<IllegalArgumentException> { GuidedPathNetwork.builder("  ") }
    }

    // ---- what is deliberately legal -----------------------------------------------------------

    @Test
    fun `a built network cannot be altered, so nothing about it can differ between replications`() {
        val builder = GuidedPathNetwork.builder("N")
            .link("L1", "A", "B", length = 12.0, zoneLength = 12.0)
        builder.build()
        assertFailsWith<IllegalStateException> {
            builder.link("L2", "B", "C", length = 12.0, zoneLength = 12.0)
        }
        assertFailsWith<IllegalStateException> { builder.intersection("C") }
        assertFailsWith<IllegalStateException> { builder.station("Dock", "A") }
        assertFailsWith<IllegalStateException> { builder.build() }
    }

    @Test
    fun `a disconnected network is legal at construction because model building is incremental`() {
        val net = GuidedPathNetwork.builder("N")
            .link("L1", "A", "B", length = 12.0, zoneLength = 12.0)
            .link("L2", "C", "D", length = 12.0, zoneLength = 12.0)
            .build()
        val a = net.intersection("A")!!
        val d = net.intersection("D")!!
        assertTrue(!net.isReachable(a, d))
    }

    @Test
    fun `a derived zone length is not rejected for a rounding error the modeler cannot see`() {
        // A length of one divided into 49 zones does not multiply back to exactly one: the product
        // is 0.9999999999999999. Comparing exactly would reject a link the modeler specified
        // correctly, which is why the check is a relative tolerance rather than equality.
        val zoneLength = 1.0 / 49.0
        assertTrue(zoneLength * 49 != 1.0, "the premise of this test no longer holds")
        val data = LinkData("L1", "A", "B", length = 1.0, zoneLength = zoneLength, numZones = 49)
        assertTrue(data.numZones == 49)
    }

    @Test
    fun `the tolerance does not swallow a geometry error a modeler would want to hear about`() {
        // Two feet out of fifty is far outside the tolerance and is a real specification error.
        assertMessageNames(
            { LinkData("L1", "A", "B", length = 50.0, zoneLength = 12.0, numZones = 4) },
            "remainder"
        )
        // Even a tenth of a foot out of forty-eight is caught.
        assertMessageNames(
            { LinkData("L1", "A", "B", length = 48.1, zoneLength = 12.0, numZones = 4) },
            "remainder"
        )
    }
}
