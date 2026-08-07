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

package ksl.modeling.agent

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Phase C7 — the agent layer's animation emission surface.
 *
 *  `reportAnimationState`, `reportPlannedPath`, `reportMarkerPulse` and the position
 *  chokepoints in the projections had **no test reference**. They are exercised by
 *  examples, but nothing asserted that the right events reach the sink — and the
 *  animation trace is the modeller's main instrument for seeing whether a model
 *  behaves, so a silent emission regression would be felt as "the animation looks
 *  wrong" long before anyone suspected the emitters.
 *
 *  Everything here is gated on `AnimationSink.isActive`, so the second property
 *  worth pinning is that emission costs nothing when no sink is installed.
 */
class AgentAnimationEmissionTest {

    /** Records everything emitted, in order. */
    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean get() = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    /** A sink that refuses to collect, to prove emission is gated. */
    private class InactiveSink : AnimationSink {
        var emitCalls: Int = 0
        override val isActive: Boolean get() = false
        override fun emit(event: AnimationEvent) { emitCalls++ }
    }

    private class EmittingModel(
        parent: ModelElement,
        val enableOverlays: Boolean = false,
    ) : AgentModel(parent, "emitter") {
        val ctx: Context<Walker> = Context("walkers")
        val grid: GridProjection<Walker> = GridProjection(ctx, columns = 8, rows = 8)

        inner class Walker(aName: String) : Agent(aName)

        val walker = Walker("walker")

        override fun initialize() {
            super.initialize()
            ctx.add(walker)
            grid.placeAt(walker, Cell(1, 1))
            // Planned paths and marker pulses are opt-in overlay chrome, gated off by
            // default; the animation coordinator turns them on from an OverlaySpec.
            if (enableOverlays) {
                setCapturePlannedPaths(true)
                setCaptureMarkerPulses(true)
            }

            schedule(EventActionIfc<Nothing> {
                walker.reportAnimationState("Busy")
            }, 1.0)
            schedule(EventActionIfc<Nothing> {
                grid.moveTo(walker, Cell(2, 1))
            }, 2.0)
            schedule(EventActionIfc<Nothing> {
                reportPlannedPath("walker", listOf(Point2D(2.0, 1.0), Point2D(5.0, 1.0)))
            }, 3.0)
            schedule(EventActionIfc<Nothing> {
                reportMarkerPulse(4.0, 4.0, holdTime = 2.0, label = "ping", colorHex = "#ff0000")
            }, 4.0)
        }
    }

    private fun runWith(
        sink: AnimationSink,
        length: Double = 6.0,
        enableOverlays: Boolean = false,
    ): EmittingModel {
        val model = Model("animEmission")
        model.animationSink = sink
        val m = EmittingModel(model, enableOverlays)
        model.numberOfReplications = 1
        model.lengthOfReplication = length
        model.simulate()
        return m
    }

    // ── State reporting ──────────────────────────────────────────────────────

    /**
     *  `reportAnimationState` is what drives state-based colouring for a model with
     *  no statechart — `GridEpidemicExample` colours its SIR population this way.
     */
    @Test
    @DisplayName("C7: reportAnimationState emits a state event naming the agent")
    fun reportAnimationStateEmits() {
        val sink = CollectingSink()
        runWith(sink)
        val entered = sink.events.filterIsInstance<AnimationEvent.AgentStateEntered>()
        assertTrue(entered.any { it.stateName == "Busy" }, "expected a Busy state event")
        assertTrue(
            entered.any { it.agentName == "walker" },
            "the event must name the agent; got ${entered.map { it.agentName }}",
        )
    }

    // ── Position chokepoint ──────────────────────────────────────────────────

    /**
     *  Projection placement and movement are the universal emission chokepoint: an
     *  agent that moves must be redrawn. Both the initial placement and the later
     *  move have to arrive, or the renderer would show an agent stuck at its start.
     */
    @Test
    @DisplayName("C7: grid placement and movement both emit position events")
    fun placementAndMovementEmitPositions() {
        val sink = CollectingSink()
        runWith(sink)
        val positions = sink.events
            .filterIsInstance<AnimationEvent.AgentPositionChanged>()
            .filter { it.agentName == "walker" }
        assertTrue(positions.size >= 2, "expected placement and move; got ${positions.size}")
        assertEquals(1.0, positions.first().x, 1e-9, "placed at column 1")
        assertEquals(2.0, positions.last().x, 1e-9, "moved to column 2")
    }

    // ── Overlay chrome ───────────────────────────────────────────────────────

    /**
     *  Planned paths and marker pulses are **opt-in**: both are gated by capture
     *  flags that default to off, which the animation coordinator sets from an
     *  `OverlaySpec`. So the first thing to pin is that a model which has not asked
     *  for them emits nothing — otherwise every model would pay for debugging chrome
     *  it never requested.
     */
    @Test
    @DisplayName("C7: planned paths and marker pulses are silent unless enabled")
    fun overlayChromeIsOptIn() {
        val sink = CollectingSink()
        runWith(sink, enableOverlays = false)
        assertTrue(
            sink.events.none { it is AnimationEvent.PlannedPath },
            "planned paths must be opt-in",
        )
        assertTrue(
            sink.events.none { it is AnimationEvent.MarkerPulsed },
            "marker pulses must be opt-in",
        )
    }

    @Test
    @DisplayName("C7: reportPlannedPath emits the path once enabled")
    fun plannedPathEmitsWhenEnabled() {
        val sink = CollectingSink()
        runWith(sink, enableOverlays = true)
        val paths = sink.events.filterIsInstance<AnimationEvent.PlannedPath>()
        assertTrue(paths.isNotEmpty(), "expected a planned-path event")
        assertEquals("walker", paths.first().agentName)
    }

    @Test
    @DisplayName("C7: reportMarkerPulse emits the marker once enabled")
    fun markerPulseEmitsWhenEnabled() {
        val sink = CollectingSink()
        runWith(sink, enableOverlays = true)
        val markers = sink.events.filterIsInstance<AnimationEvent.MarkerPulsed>()
        assertTrue(markers.isNotEmpty(), "expected a marker event")
        val marker = markers.first()
        assertEquals(4.0, marker.x, 1e-9)
        assertEquals(4.0, marker.y, 1e-9)
        assertEquals("ping", marker.label)
    }

    // ── Gating ───────────────────────────────────────────────────────────────

    /**
     *  Every emission site checks `isActive` first, so a model with no animation
     *  pays nothing. This is the property that lets the emitters be sprinkled
     *  through hot paths like `placeAt`, so it is worth guarding: an emitter that
     *  forgot the check would cost every model, animated or not.
     */
    @Test
    @DisplayName("C7: nothing is emitted when the sink is inactive")
    fun inactiveSinkReceivesNothing() {
        val sink = InactiveSink()
        runWith(sink)
        assertEquals(0, sink.emitCalls, "an inactive sink must never be called")
    }

    /** A model with no sink installed at all must simply run. */
    @Test
    @DisplayName("C7: a model with no sink runs unaffected")
    fun noSinkIsHarmless() {
        val model = Model("noSink")
        val m = EmittingModel(model, enableOverlays = true)
        model.numberOfReplications = 1
        model.lengthOfReplication = 6.0
        model.simulate()
        assertEquals(Cell(2, 1), m.grid.cellOf(m.walker), "the model still ran")
    }
}
