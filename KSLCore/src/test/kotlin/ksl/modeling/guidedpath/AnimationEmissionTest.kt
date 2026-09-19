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

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.animation.GuidedPathIntersectionDef
import ksl.animation.GuidedPathLinkDef
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  What reaches an animation sink from a guide path, and what it costs when nobody is watching.
 *
 *  Two things are asserted and they matter for different reasons.
 *
 *  The first is that emission is *gated*. Every emitting method asks the model whether a sink is
 *  active before doing anything, so a run without animation pays a field read. A regression here
 *  would be invisible -- the model would still be right, just slower -- which is precisely the kind
 *  of thing that never gets noticed until somebody profiles a long run.
 *
 *  The second is the wire format. `AnimationEvent`'s own documentation says the string tags are
 *  stable and should not be changed casually: they are what a renderer, a recorded trace file, and
 *  any tooling built on either agree upon, and the Kotlin class names can be refactored freely
 *  underneath them. So the tags are pinned here as serialized JSON, and a rename that would break
 *  an existing recording fails this test rather than being discovered by a viewer that silently
 *  ignores an unknown event.
 */
class AnimationEmissionTest {

    /** Records everything emitted, in order. */
    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean get() = true
        override fun emit(event: AnimationEvent) {
            events.add(event)
        }
    }

    /** Reports itself inactive but counts anything that arrives anyway. */
    private class InactiveSink : AnimationSink {
        var emitCalls: Int = 0
        override val isActive: Boolean get() = false
        override fun emit(event: AnimationEvent) {
            emitCalls++
        }
    }

    /** One cart running a short one-way path with a spur off the end of it. */
    private class Shop(parent: ModelElement) : ModelElement(parent, "Shop") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Animated")
            .intersection("A", x = 0.0, y = 0.0)
            .intersection("B", x = 24.0, y = 0.0)
            .intersection("C", x = 24.0, y = -12.0)
            .link("Main", "A", "B", length = 24.0, zoneLength = 12.0, beginDirection = 0.0)
            .link(
                "Branch", "B", "C", length = 12.0, zoneLength = 12.0,
                type = LinkType.SPUR, beginDirection = 270.0
            )
            .build()

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(10.0), 1, EndOfZoneControl(), "Cart"
        )

        override fun initialize() {
            schedule({ _: KSLEvent<Nothing> -> cart.sendTo("C") }, 0.0)
        }
    }

    /** A crew: two members, made during the run, holding space no vehicle owns. */
    private class Crew(id: Int) : ZoneHolderIfc {
        override val name: String = "Crew$id"
        override val awaitedZone: Zone? get() = null
    }

    private class SilentAction : ZoneHoldActionIfc {
        override fun holdBegan(allocation: ZoneAllocation) = Unit
        override fun holdEnded(allocation: ZoneAllocation) = Unit
    }

    /**
     *  The same layout with a closure on it, and a cart that runs into it.
     *
     *  Separate from [Shop] rather than folded into it, so the cart-only expectations above stay
     *  about a cart only. The crew asks at 1.0 for the two zones of Main while the cart is crossing
     *  the first of them, so the closure is genuinely pending before it is granted -- an immediate
     *  grant would emit RESERVED and HELD in one instant and prove less.
     */
    private class ClosureShop(parent: ModelElement) : ModelElement(parent, "ClosureShop") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Animated")
            .intersection("A", x = 0.0, y = 0.0)
            .intersection("B", x = 24.0, y = 0.0)
            .link("Main", "A", "B", length = 24.0, zoneLength = 12.0, beginDirection = 0.0)
            .build()

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, EndOfZoneControl(), "Cart"
        )
        val action = SilentAction()

        /** Set by the test: whether the crew gives the request up instead of waiting for it. */
        var abandonAt: Double = Double.NaN

        override fun initialize() {
            val crew = Crew(1)
            schedule({ _: KSLEvent<Nothing> -> cart.sendTo("B") }, 0.0)
            schedule({ _: KSLEvent<Nothing> ->
                system.holdZonesFor(crew, network.link("Main")!!.zones, 5.0, action)
            }, 1.0)
            if (abandonAt.isFinite()) {
                schedule({ _: KSLEvent<Nothing> -> system.releaseZones(crew) }, abandonAt)
            }
        }
    }

    private fun runClosure(sink: AnimationSink, abandonAt: Double = Double.NaN): ClosureShop {
        val m = Model("AnimatedClosure")
        val shop = ClosureShop(m)
        shop.abandonAt = abandonAt
        m.animationSink = sink
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        return shop
    }

    private fun runWith(sink: AnimationSink): Shop {
        val m = Model("AnimatedRun")
        val shop = Shop(m)
        m.animationSink = sink
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("The guide path is emitted once per replication, with its own coordinates")
    fun theGuidePathIsEmitted() {
        val sink = CollectingSink()
        runWith(sink)
        val defined = sink.events.filterIsInstance<AnimationEvent.GuidedPathDefined>()
        assertEquals(1, defined.size, "once per replication, before anything moves")
        val event = defined.single()
        assertEquals("Animated", event.networkName)
        assertEquals(3, event.intersections.size)
        assertEquals(2, event.links.size)

        // Carrying coordinates is what lets the guide path be drawn with no authored layout at all,
        // unlike a conveyor, which has only anchors and must be positioned by hand.
        val b = assertNotNull(event.intersections.firstOrNull { it.name == "B" })
        assertEquals(24.0, b.x, 1e-9)
        assertEquals(0.0, b.y, 1e-9)

        val branch = assertNotNull(event.links.firstOrNull { it.name == "Branch" })
        assertEquals("B", branch.from)
        assertEquals("C", branch.to)
        assertEquals(1, branch.numZones)
        assertTrue(branch.spur, "a viewer reading congestion needs to see which links are spurs")
        assertTrue(!branch.bidirectional)
    }

    @Test
    @DisplayName("Every zone the cart enters is reported, in order")
    fun movementIsSampledOnZoneEntry() {
        val sink = CollectingSink()
        runWith(sink)
        val moves = sink.events.filterIsInstance<AnimationEvent.GuidedTransporterMoved>()
        val zones = moves.map { it.zoneName }
        // Where it starts, then each zone of the route: two on Main, the junction B, and the single
        // zone of the spur down to C, then C itself.
        assertEquals(
            listOf("A", "Main.Zone1", "Main.Zone2", "B", "Branch.Zone1", "C"), zones,
            "the renderer interpolates between these, so a missing or out-of-order sample puts " +
                    "the cart somewhere it never was"
        )
        val onLink = assertNotNull(moves.firstOrNull { it.zoneName == "Main.Zone2" })
        assertEquals("Main", onLink.linkName)
        assertEquals(2, onLink.zoneIndex, "one-based from the link's begin end")
        val atJunction = assertNotNull(moves.firstOrNull { it.zoneName == "B" })
        assertEquals(null, atJunction.linkName, "an intersection belongs to no link")
        assertEquals(0, atJunction.zoneIndex)
    }

    @Test
    @DisplayName("State changes are reported so stillness can be told apart")
    fun stateChangesAreEmitted() {
        val sink = CollectingSink()
        runWith(sink)
        val states = sink.events.filterIsInstance<AnimationEvent.GuidedTransporterStateChanged>()
        val seen = states.map { it.state }
        assertTrue(seen.contains(TransporterState.IDLE.name), seen.toString())
        assertTrue(
            seen.contains(TransporterState.RETURNING_HOME.name),
            "sendTo moves a transporter on its own account, which is that state: $seen"
        )
        assertEquals(
            TransporterState.IDLE.name, seen.last(),
            "the cart finishes parked, and a viewer must be told so rather than left to infer it " +
                    "from the absence of movement -- which is also what blocking looks like"
        )
    }

    @Test
    @DisplayName("Nothing is emitted when no sink is active")
    fun emissionIsGated() {
        val sink = InactiveSink()
        runWith(sink)
        assertEquals(
            0, sink.emitCalls,
            "every emitting method must check the sink first, so that a run without animation " +
                    "pays a field read and nothing else"
        )
    }

    @Test
    @DisplayName("A closure is emitted, so a cart stopped by empty space has a visible cause")
    fun aClosureIsEmitted() {
        // The blindness this closes. A cart held up by a closure is held up by space that is empty
        // -- nothing is standing in it -- so a recording without this shows a cart stopping for no
        // reason a viewer can see.
        val sink = CollectingSink()
        runClosure(sink)
        val closures = sink.events.filterIsInstance<AnimationEvent.GuidedPathClosureChanged>()
        assertEquals(
            listOf("RESERVED", "HELD", "RELEASED"), closures.map { it.state },
            "a closure that drains before it is granted passes through all three, in order"
        )
        for (event in closures) {
            assertEquals("Crew1", event.holderName)
            assertEquals("Animated", event.networkName)
            assertEquals(
                listOf("Main.Zone1", "Main.Zone2"), event.zoneNames,
                "the whole set, because a closure is taken all at once or not at all"
            )
        }
        // RESERVED at the ask, HELD only once the cart has left the zones it wanted.
        assertEquals(1.0, closures[0].simTime, 1e-9)
        assertTrue(closures[1].simTime > closures[0].simTime, "the grant must follow the drain")
        assertEquals(closures[1].simTime + 5.0, closures[2].simTime, 1e-9, "held for its full term")
    }

    @Test
    @DisplayName("A reservation given up is released, so nothing stays shaded that has gone")
    fun anAbandonedReservationIsReleased() {
        // The case a renderer would otherwise leave shaded for ever: a closure that never became a
        // hold. RELEASED is the only event that can follow its RESERVED.
        val sink = CollectingSink()
        runClosure(sink, abandonAt = 1.5)
        val closures = sink.events.filterIsInstance<AnimationEvent.GuidedPathClosureChanged>()
        assertEquals(listOf("RESERVED", "RELEASED"), closures.map { it.state })
        assertEquals(1.5, closures.last().simTime, 1e-9)
    }

    @Test
    @DisplayName("Closures are gated by the sink exactly as everything else is")
    fun closuresAreGatedToo() {
        val sink = InactiveSink()
        runClosure(sink)
        assertEquals(
            0, sink.emitCalls,
            "a closure emission must ask the sink first, like every other emitting method"
        )
    }

    @Test
    @DisplayName("The wire tags are pinned, because recorded traces depend on them")
    fun theTagsAreStable() {
        // Encoded through the trace format's own canonical writer rather than a locally configured
        // one, so what is pinned is the line a recording actually contains -- discriminator field
        // name and all -- and not merely this test's idea of it.
        //
        // AnimationEvent's own documentation calls these tags stable and says they should not be
        // changed casually. The Kotlin class names may be refactored freely; these strings may not,
        // because an existing recording is written in terms of them.
        //
        // The intersection gained `"z":0.0` when junctions gained a height. That is additive rather
        // than a broken promise: the tags are unchanged, the format writes defaulted fields by
        // design -- `encodeDefaults = true`, whose own KDoc names `z = 0.0` as the example -- and a
        // recording made before heights existed still decodes, which `IntersectionHeightTest`
        // asserts against this same writer.
        assertEquals(
            """{"event":"GuidedPathDefined","simTime":0.0,"networkName":"N","intersections":[{"name":"A","x":1.0,"y":2.0,"z":0.0}],"links":[{"name":"L","from":"A","to":"B","numZones":3,"bidirectional":false,"spur":true}]}""",
            AnimationEvent.encodeToLine(
                AnimationEvent.GuidedPathDefined(
                    0.0, "N",
                    listOf(GuidedPathIntersectionDef("A", 1.0, 2.0)),
                    listOf(GuidedPathLinkDef("L", "A", "B", 3, bidirectional = false, spur = true))
                )
            )
        )
        assertEquals(
            """{"event":"GuidedTransporterMoved","simTime":1.5,"transporterName":"Cart","networkName":"N","zoneName":"L.Zone2","linkName":"L","zoneIndex":2}""",
            AnimationEvent.encodeToLine(
                AnimationEvent.GuidedTransporterMoved(1.5, "Cart", "N", "L.Zone2", "L", 2)
            )
        )
        assertEquals(
            """{"event":"GuidedTransporterStateChanged","simTime":2.0,"transporterName":"Cart","networkName":"N","state":"BLOCKED"}""",
            AnimationEvent.encodeToLine(
                AnimationEvent.GuidedTransporterStateChanged(2.0, "Cart", "N", "BLOCKED")
            )
        )
        assertEquals(
            """{"event":"GuidedPathClosureChanged","simTime":3.0,"holderName":"Crew1","networkName":"N","zoneNames":["L.Zone1","L.Zone2"],"state":"HELD"}""",
            AnimationEvent.encodeToLine(
                AnimationEvent.GuidedPathClosureChanged(
                    3.0, "Crew1", "N", listOf("L.Zone1", "L.Zone2"), "HELD"
                )
            )
        )
    }

    @Test
    @DisplayName("Every emitted event survives a round trip through the trace format")
    fun everyEventRoundTrips() {
        // A tag that serializes but will not parse back is worse than one that does neither: the
        // recording is written, looks fine, and fails only when someone tries to replay it.
        val sink = CollectingSink()
        runWith(sink)
        runClosure(sink)
        val guided = sink.events.filter {
            it is AnimationEvent.GuidedPathDefined ||
                    it is AnimationEvent.GuidedTransporterMoved ||
                    it is AnimationEvent.GuidedTransporterStateChanged ||
                    it is AnimationEvent.GuidedPathClosureChanged
        }
        assertTrue(
            guided.any { it is AnimationEvent.GuidedPathClosureChanged },
            "the closure fixture must have contributed some, or this checks three tags of four"
        )
        assertTrue(guided.size >= 8, "the run must have produced events to check: ${guided.size}")
        for (event in guided) {
            assertEquals(
                event, AnimationEvent.decodeFromLine(AnimationEvent.encodeToLine(event)),
                "an event that cannot be read back makes the recording it appears in unreplayable"
            )
        }
    }
}
