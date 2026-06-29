package ksl.app.swing.animation.app

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.animation.MoverMode
import ksl.animation.SpaceInfo
import ksl.animation.TraceFileReader
import ksl.app.session.RunResult
import ksl.examples.general.animationbundle.Example03GridEpidemic
import ksl.examples.general.animationbundle.Example08ConveyorTandem
import ksl.examples.general.animationbundle.Example13MovableResources
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ReplayModel
import ksl.app.swing.animation.replay.autoLayout
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * V7: the authoring app runs and replays the agent-based and conveyor paradigms end-to-end — not just the
 * process-with-movement demo. Agents resolve to positions (via the trace-derived grid space); the conveyor
 * belt resolves once its anchor locations are placed (Quick view does this).
 */
class ParadigmEndToEndTest {

    private fun builderOf(make: () -> Model, len: Double) = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            make().apply { numberOfReplications = 1; lengthOfReplication = len }
    }

    private fun run(label: String, builder: ModelBuilderIfc): Pair<AnimationAppController, List<AnimationEvent>> {
        val c = AnimationAppController(label, builder).apply { workspaceOverride = Files.createTempDirectory("e2e") }
        c.submit()
        val r = runBlocking { withTimeout(120_000) { c.lastResult.filterNotNull().first() } }
        assertIs<RunResult.Completed>(r)
        return c to TraceFileReader.readAll(c.lastTraceFile.value!!).second
    }

    @Test
    fun `a transport mover emits TRANSPORTING moves carrying an entity (C2)`() {
        val (c, events) = run("mover", builderOf({ Example13MovableResources.buildModel() }, 480.0))
        try {
            val moves = events.filterIsInstance<AnimationEvent.SpatialElementMoved>()
            assertTrue(moves.isNotEmpty(), "the transport workers move")
            val carrying = moves.filter { it.mode == MoverMode.TRANSPORTING }
            assertTrue(carrying.isNotEmpty(), "at least one move is a TRANSPORTING (carrying) move")
            assertTrue(carrying.all { it.carriedEntityId != null }, "a transporting move names the carried entity")
            assertTrue(moves.any { it.mode == MoverMode.EMPTY }, "and there are empty repositioning moves too")
        } finally { c.close() }
    }

    @Test
    fun `an agent model runs and its agents resolve to positions`() {
        val (c, events) = run("agent", builderOf({ Example03GridEpidemic.buildModel() }, 40.0))
        try {
            assertTrue(c.inventory.spaces.any { it.kind == SpaceInfo.SpaceKind.GRID }, "the grid space is in the inventory")
            // No authored layout: agents render against the trace-derived space.
            val model = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events))
            assertTrue(model.agentNames.isNotEmpty(), "agents discovered")
            val name = model.agentNames.first()
            val tMax = events.maxOf { it.simTime }
            val resolved = generateSequence(0.0) { it + tMax / 50.0 }.takeWhile { it <= tMax }
                .any { model.agentPositionAt(name, it) != null }
            assertTrue(resolved, "agent '$name' resolves to a position at some time (it moves on the grid)")
        } finally { c.close() }
    }

    @Test
    fun `a conveyor model runs and its belt resolves with quick-view anchors`() {
        val (c, events) = run("conveyor", builderOf({ Example08ConveyorTandem.buildModel() }, 60.0))
        try {
            assertContains(c.inventory.conveyors, "Conveyor")
            val define = events.filterIsInstance<AnimationEvent.ConveyorDefined>().first()
            // Quick view places the conveyor's anchor locations as stations, so the belt resolves.
            val probe = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events))
            assertContains(probe.conveyorNames, "Conveyor")
            val auto = probe.autoLayout(events)
            define.anchorLocations.forEach { assertContains(auto.stations.map { s -> s.stationName }, it) }
            val model = ReplayModel.build(AnimationSource(layout = auto, header = AnimationTraceHeader(), events = events))
            val cell = define.anchorCells.first()
            val p = model.conveyorCellPosition("Conveyor", cell)
            assertTrue(p != null && p.x in 0.0..auto.width && p.y in 0.0..auto.height,
                "the conveyor belt cell resolves on-canvas; was $p")
        } finally { c.close() }
    }
}
