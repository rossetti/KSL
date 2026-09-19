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

import kotlinx.serialization.json.Json
import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.animation.GuidedPathIntersectionDef
import ksl.modeling.guidedpath.spec.IntersectionData
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  A junction's **height**, and the one thing that must not follow from having one.
 *
 *  A guide path could always be built on more than one floor -- a lift is a link whose length is the
 *  height of the shaft, and the zone rules already admit one vehicle to it at a time -- but an
 *  `Intersection` carried only `x` and `y`. `LocationIfc.z` existed and defaulted to zero, so a
 *  multi-floor network *behaved* correctly and *drew flat*, and the guide told modellers to offset
 *  their upper floors in `y` and live with it. This closes that.
 *
 *  ## What must not happen
 *
 *  The load-bearing claim is the negative one: **a height changes nothing.** This is a network
 *  spatial model, so every distance comes from the routing matrix and every travel time from a
 *  link's declared length; coordinates are layout and are read by nothing else. If giving a floor a
 *  height changed a single arrival time, the coordinate would have quietly become part of the
 *  physics and every model already written would be at risk. So the same network is built twice, once
 *  flat and once on two floors, and the two are held to **bit-identical** routing and arrival times.
 *
 *  That test is the reason to trust the feature, and it is worth more than the three that check the
 *  height arrives where it was sent.
 *
 *  ## Why the default is zero and not not-a-number
 *
 *  `x` and `y` default to not-a-number, which distinguishes "the modeller supplied no layout" from
 *  "the modeller put it at the origin" -- a distinction a renderer needs, because it decides whether
 *  to lay the network out itself. Height has no such ambiguity: a guide path with no layout is a flat
 *  one, and flat means zero. A third state would have to be handled by every reader, and would poison
 *  any renderer that added it to a camera. Code testing `x.isNaN()` for "no layout" still gets the
 *  right answer, which is why the asymmetry is deliberate rather than an oversight, and is asserted.
 */
class IntersectionHeightTest {

    private companion object {
        const val SHAFT = 12.0

        /**
         *  Two floors joined by a lift, or the same thing drawn flat.
         *
         *  Identical in every respect a simulation reads: the same links, the same lengths, the same
         *  zones. They differ only in where a renderer would put the upper floor.
         */
        fun twoFloors(name: String, height: Double): GuidedPathNetwork =
            GuidedPathNetwork.builder(name)
                .intersection("G1", x = 0.0, y = 0.0)
                .intersection("G2", x = 40.0, y = 0.0)
                .intersection("F1", x = 40.0, y = 0.0, z = height)
                .intersection("F2", x = 0.0, y = 0.0, z = height)
                .link("Ground", "G1", "G2", length = 40.0, zoneLength = 10.0, beginDirection = 0.0)
                .link("Up", "G2", "F1", length = SHAFT, zoneLength = SHAFT, beginDirection = 90.0)
                .link("First", "F1", "F2", length = 40.0, zoneLength = 10.0, beginDirection = 180.0)
                .link("Down", "F2", "G1", length = SHAFT, zoneLength = SHAFT, beginDirection = 270.0)
                .build()
    }

    // ---- the claim that matters -----------------------------------------------------------------

    /** One cart going round the two floors, reporting when it reaches each junction. */
    private class Circuit(parent: ModelElement, network: GuidedPathNetwork) :
        ModelElement(parent, "Circuit") {

        val network: GuidedPathNetwork = network

        init {
            spatialModel = network
        }

        val space = GuidedPathSpace(this, network, name = "Space")
        val cart = GuidedTransporter(space, TransporterPlacement.At("G1"), ConstantRV(10.0), name = "Cart")

        val arrivals = mutableListOf<Pair<String, Double>>()
        private val tour = listOf("G2", "F1", "F2", "G1")
        private var leg = 0

        init {
            cart.attachArrivalListener {
                arrivals.add(it.currentLocation.name to time)
                if (++leg < tour.size) it.sendTo(tour[leg])
            }
        }

        override fun initialize() {
            arrivals.clear()
            leg = 0
            schedule({ _: KSLEvent<Nothing> -> cart.sendTo(tour[0]) }, 0.0)
        }
    }

    private fun runCircuit(height: Double): Circuit {
        val m = Model("Circuit-$height")
        val c = Circuit(m, twoFloors("Net", height))
        c.space.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        return c
    }

    @Test
    @DisplayName("putting a floor at a height changes nothing a simulation can observe")
    fun aHeightIsLayoutAndNothingElse() {
        val flat = runCircuit(0.0)
        val stacked = runCircuit(SHAFT)

        // Routing first, because if the matrices differ nothing downstream means anything. Every
        // ordered pair, not a sample: the matrix is small and a partial check is not a check.
        for (from in flat.network.intersections) {
            for (to in flat.network.intersections) {
                val a = flat.network.distance(from, to)
                val b = stacked.network.distance(
                    stacked.network.intersection(from.name)!!, stacked.network.intersection(to.name)!!
                )
                assertEquals(a, b, 0.0, "the height changed the distance from ${from.name} to ${to.name}")
            }
        }

        // And then the run itself, to the bit. A coordinate that had leaked into the physics would
        // move these; nothing else would.
        assertEquals(4, flat.arrivals.size, "the flat circuit did not finish: ${flat.arrivals}")
        assertEquals(
            flat.arrivals, stacked.arrivals,
            "the same circuit on two floors arrived at different times than drawn flat"
        )
        // A lap is 40 + 12 + 40 + 12 at velocity 10, so the last arrival is at 10.4 exactly. Stated
        // as an identity, so that "identical" cannot be satisfied by two runs that both did nothing.
        assertEquals(
            (40.0 + SHAFT + 40.0 + SHAFT) / 10.0, flat.arrivals.last().second, 1.0e-9,
            "the circuit did not take the lap time its links describe: ${flat.arrivals}"
        )
    }

    // ---- the height arrives where it was sent ---------------------------------------------------

    @Test
    @DisplayName("a junction reports the height it was built with, and zero when it was given none")
    fun aJunctionCarriesItsHeight() {
        val net = twoFloors("Heights", SHAFT)
        assertEquals(SHAFT, net.intersection("F1")!!.z, 0.0)
        assertEquals(0.0, net.intersection("G1")!!.z, 0.0)

        // The deliberate asymmetry: no layout at all means no planar position and ground level.
        val bare = GuidedPathNetwork.builder("Bare")
            .link("L", "A", "B", length = 10.0, zoneLength = 10.0)
            .build()
        val a = bare.intersection("A")!!
        assertTrue(a.x.isNaN(), "an unplaced junction should have no abscissa")
        assertTrue(a.y.isNaN(), "an unplaced junction should have no ordinate")
        assertFalse(a.z.isNaN(), "height has no 'unspecified' state and must never be not-a-number")
        assertEquals(0.0, a.z, 0.0, "an unplaced junction is at ground level")
    }

    @Test
    @DisplayName("heights survive a round trip through the network specification")
    fun heightsRoundTrip() {
        val net = twoFloors("Round", SHAFT)
        val rebuilt = GuidedPathNetwork.fromJson(net.settingsToJson())
        for (i in net.intersections) {
            assertEquals(i.z, rebuilt.intersection(i.name)!!.z, 0.0, "the height of ${i.name} did not survive")
        }

        // A specification written before heights existed must still decode, and decode as the flat
        // network it described. Omitting the field is how every such document is shaped.
        val old = """{"name":"A","length":0.0,"velocityFactor":1.0,"x":1.0,"y":2.0}"""
        val decoded = Json.decodeFromString<IntersectionData>(old)
        assertEquals(0.0, decoded.z, 0.0, "a specification with no height should decode as flat")
        assertEquals(1.0, decoded.x, 0.0)
    }

    // ---- and reaches the renderer ----------------------------------------------------------------

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean get() = true
        override fun emit(event: AnimationEvent) {
            events.add(event)
        }
    }

    @Test
    @DisplayName("the animation payload carries the height, which is the whole point of having one")
    fun theHeightReachesTheAnimationPayload() {
        val m = Model("Animated")
        val sink = CollectingSink()
        m.animationSink = sink
        Circuit(m, twoFloors("Drawn", SHAFT))
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()

        val defined = sink.events.filterIsInstance<AnimationEvent.GuidedPathDefined>().first()
        val f1 = defined.intersections.first { it.name == "F1" }
        assertEquals(SHAFT, f1.z, 0.0, "the upper floor was emitted at ground level")
        assertEquals(0.0, defined.intersections.first { it.name == "G1" }.z, 0.0)
        // Nothing else needs one: a transporter is reported by zone, and the renderer interpolates
        // between the two ends of a link -- so a cart on a lift climbs for the same reason a cart on
        // an aisle moves sideways, and there is no second place to get it wrong.
        assertTrue(
            sink.events.any { it is AnimationEvent.GuidedTransporterMoved },
            "the cart never moved, so the interpolation this relies on was never exercised"
        )

        // What a recording actually contains, through the trace format's own writer rather than a
        // locally configured one -- the two differ, and it is the trace format that matters. It sets
        // `encodeDefaults = true` on purpose, so that a renderer never has to know a Kotlin default
        // to read a line, and a flat junction is therefore written as an explicit ground level
        // rather than by omission.
        assertEquals(
            """{"event":"GuidedPathDefined","simTime":0.0,"networkName":"N",""" +
                    """"intersections":[{"name":"A","x":1.0,"y":2.0,"z":12.0}],"links":[]}""",
            AnimationEvent.encodeToLine(
                AnimationEvent.GuidedPathDefined(
                    0.0, "N", listOf(GuidedPathIntersectionDef("A", 1.0, 2.0, SHAFT))
                )
            )
        )
        // The direction that has to keep working: a trace recorded before heights existed replays as
        // the flat network it described, rather than failing to parse.
        val archived = """{"event":"GuidedPathDefined","simTime":0.0,"networkName":"N",""" +
                """"intersections":[{"name":"A","x":1.0,"y":2.0}],"links":[]}"""
        val replayed = AnimationEvent.decodeFromLine(archived) as AnimationEvent.GuidedPathDefined
        assertEquals(0.0, replayed.intersections.single().z, 0.0, "an archived trace did not replay flat")
    }
}
