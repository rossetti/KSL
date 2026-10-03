/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.vehiclebundle

import ksl.animation.AnimationCapture
import ksl.animation.AnimationEvent
import ksl.animation.TraceFileReader
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.ReplayModel
import ksl.app.animation.replay.TransporterSnapshot
import ksl.app.animation.replay.autoLayout
import ksl.app.animation.replay.guidedPathPreviewEvents
import ksl.animation.animationInventory
import ksl.app.animation.geom.ViewTransform
import ksl.app.animation.scene.Java2dSurface
import ksl.app.animation.scene.SceneBuilder
import ksl.app.animation.scene.SceneRenderer
import java.awt.image.BufferedImage
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Paths
import java.nio.file.Files
import kotlin.math.abs

/**
 * Every shipped vehicle model, replayed. The contract test beside this one checks that a trace carries what a
 * renderer needs; this checks that the replay actually turns it into something drawable: every transporter has
 * a position at every instant once placed, every carried load is drawn on the vehicle carrying it, a cart does
 * not move while it is standing still by its own account, and auto-layout neither draws a vehicle twice nor
 * places the queues the vehicle machinery keeps for itself.
 */
class VehicleReplayTest {

    private val builders: List<ModelBuilderIfc> = listOf(
        SimpleAgvShopModelBuilder(), GuidePathDisturbancesModelBuilder(), MultiFloorHospitalModelBuilder(),
        TwoLaneWarehouseModelBuilder(), FreePathFleetYardModelBuilder(), PassiveTransporterShopModelBuilder(),
        ActiveFleetShopModelBuilder(), DispatchingRulesRingShopModelBuilder(), PedestrianCrossingModelBuilder(),
        TestAndRepairShopWithGuidedTransportersModelBuilder()
    )

    private fun trace(builder: ModelBuilderIfc, length: Double = 300.0): List<AnimationEvent> =
        traceWithHeader(builder, length).second

    private fun traceWithHeader(
        builder: ModelBuilderIfc, length: Double
    ): Pair<ksl.animation.AnimationTraceHeader, List<AnimationEvent>> {
        val m = builder.build(null, null as ExperimentRunParametersIfc?)
        m.numberOfReplications = 1
        m.lengthOfReplicationWarmUp = 0.0
        m.lengthOfReplication = minOf(m.lengthOfReplication, length)
        val file = Files.createTempFile("vehicle-replay", ".atf")
        try {
            val capture = AnimationCapture.toFile(m, file)
            m.simulate()
            capture.close()
            return TraceFileReader.readAll(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /**
     * Regenerates the web player's vehicle demo trace (`KSLAnimationCore/src/jsMain/resources/traces`). Run only
     * on request, with KSL_WRITE_VEHICLE_TRACE set to the repository root. Response observations are left out:
     * they are most of a vehicle trace and nothing the player draws without an authored plot.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "KSL_WRITE_VEHICLE_TRACE", matches = ".+")
    fun writeTheWebPlayersVehicleTrace() {
        val root = Paths.get(System.getenv("KSL_WRITE_VEHICLE_TRACE"))
        val (header, events) = traceWithHeader(SimpleAgvShopModelBuilder(), 120.0)
        val lines = listOf(header.encodeToLine()) +
            events.filter { it !is AnimationEvent.ResponseObserved }.map { AnimationEvent.encodeToLine(it) }
        val out = root.resolve("KSLAnimationCore/src/jsMain/resources/traces/SimpleAgvShop.atf")
        Files.write(out, lines)
        println("wrote ${lines.size} lines to $out")
    }

    private fun near(a: Double, b: Double) = abs(a - b) <= 1e-6 * maxOf(1.0, abs(a), abs(b))

    /**
     * Before any run, the editor's preview and the server's layout image draw each model's guide paths from its
     * inventory, with every transporter that has a home base on the path placed there.
     */
    @Test
    fun everyShippedVehicleModelPreviewsItsGuidePathsBeforeARun() {
        val problems = mutableListOf<String>()
        var placedAtHome = 0
        for (builder in builders) {
            val label = builder::class.simpleName
            val inventory = builder.build(null, null as ExperimentRunParametersIfc?).animationInventory()
            val events = guidedPathPreviewEvents(inventory.guidedPaths)
            val model = ReplayModel.build(AnimationSource(null, ksl.animation.AnimationTraceHeader(), events))
            if (model.vehicles.guidePaths.keys != inventory.guidedPaths.map { it.spaceName }.toSet()) {
                problems += "$label: preview guide paths ${model.vehicles.guidePaths.keys}"
            }
            val homed = inventory.guidedPaths.flatMap { p ->
                val places = p.intersections.flatMap { listOf(it.name) + it.aliases }.toSet()
                p.transporters.filter { it.homeBase in places }.map { it.name }
            }
            placedAtHome += homed.size
            homed.firstOrNull { model.vehicles.transporterPositionAt(it, 0.0) == null }
                ?.let { problems += "$label: $it has a home base on the path but is not placed in the preview" }
            val scene = SceneBuilder(ReplayModel.build(AnimationSource(model.autoLayout(events), ksl.animation.AnimationTraceHeader(), events))).buildStatic()
            if (inventory.guidedPaths.isNotEmpty() && scene.commandsOf("guidePaths").isEmpty()) {
                problems += "$label: the preview draws no guide path"
            }
        }
        if (problems.isNotEmpty()) fail<Unit>(problems.joinToString("\n"))
        assertTrue(placedAtHome > 0, "some shipped transporter has a home base, so placing it was exercised")
    }

    @Test
    fun everyShippedVehicleModelReplaysIntoSomethingDrawable() {
        val problems = mutableListOf<String>()
        // What the ten models exercised between them, so a replay that quietly drew nothing cannot pass.
        var transporters = 0; var loadsCarried = 0; var closures = 0; var assignments = 0; var freePathMovers = 0
        for (builder in builders) {
            val label = builder::class.simpleName
            val events = trace(builder)
            val model = ReplayModel.build(AnimationSource(null, ksl.animation.AnimationTraceHeader(), events))
            val v = model.vehicles
            transporters += v.transporterNames.size
            freePathMovers += model.spatialElementNames.size
            val (t0, t1) = model.timeRange.start to model.timeRange.endInclusive
            val samples = (0..200).map { t0 + (t1 - t0) * it / 200.0 }

            val defined = events.filterIsInstance<AnimationEvent.GuidedPathDefined>()
                .flatMap { d -> d.transporters.map { it.name } }.toSet()
            val firstPlaced = events.filterIsInstance<AnimationEvent.GuidedTransporterMoved>()
                .groupBy { it.transporterName }.mapValues { (_, e) -> e.minOf { it.simTime } }
            for (name in defined) {
                val from = firstPlaced[name]
                if (from == null) {
                    problems += "$label: transporter $name is never placed"
                    continue
                }
                samples.filter { it >= from }.firstOrNull { v.transporterPositionAt(name, it) == null }
                    ?.let { problems += "$label: $name has no position at $it" }
            }

            // A carried load is drawn on its vehicle whenever it is aboard one.
            for (t in samples) {
                closures += v.guidePaths.keys.sumOf { v.closuresAt(it, t).size }
                assignments += v.assignedVehicles.count { v.assignmentAt(it, t) != null }
                for (body in v.loadCarryingVehicles) {
                    for (id in v.loadsAboardAt(body, t)) {
                        loadsCarried++
                        if (model.carriedEntityPositionAt(id, t) == null) {
                            problems += "$label: load $id aboard $body has no position at $t"
                        }
                    }
                }
            }

            // Standing still means standing still: between two state changes that bracket a still state with no
            // zone entry in between, the front does not move.
            val states = events.filterIsInstance<AnimationEvent.GuidedTransporterStateChanged>()
            val moves = events.filterIsInstance<AnimationEvent.GuidedTransporterMoved>()
            for ((name, changes) in states.groupBy { it.transporterName }) {
                val entries = moves.filter { it.transporterName == name }.map { it.simTime }
                for ((a, b) in changes.zipWithNext()) {
                    val snapshot = TransporterSnapshot(a.state, a.halted)
                    // An interval of floating-point noise between events at one instant holds no stillness to check.
                    if (!snapshot.isStill || b.simTime - a.simTime <= 1e-9 * maxOf(1.0, abs(b.simTime))) continue
                    if (entries.any { it > a.simTime && it < b.simTime }) {
                        problems += "$label: $name entered a zone while ${snapshot.state} at ${a.simTime}"
                        continue
                    }
                    // Just before the stillness ends: a zone entered at that very instant (a zero-length
                    // intersection, the moment travel resumes) is the next movement, not movement while still.
                    val p = v.transporterPositionAt(name, a.simTime) ?: continue
                    val q = v.transporterPositionAt(name, b.simTime - (b.simTime - a.simTime) * 1e-6) ?: continue
                    if (!near(p.x, q.x) || !near(p.y, q.y)) {
                        problems += "$label: $name moved while ${snapshot.state} over ${a.simTime}..${b.simTime}"
                    }
                }
            }

            // Auto-layout draws each transporter once, on its path, and leaves the hold queues out.
            val layout = model.autoLayout(events)
            layout.resources.firstOrNull { it.resourceName in v.transporterNames || it.resourceName in v.fleetVehicleNames }
                ?.let { problems += "$label: vehicle ${it.resourceName} placed as a resource" }
            layout.queues.firstOrNull { v.isVehicleInternalQueue(it.queueName) }
                ?.let { problems += "$label: vehicle queue ${it.queueName} placed" }
            if (v.guidePaths.keys != layout.guidedPaths.map { it.spaceName }.toSet()) {
                problems += "$label: auto-layout guide paths ${layout.guidedPaths.map { it.spaceName }} != ${v.guidePaths.keys}"
            }

            // Viewed through its auto-layout, as a player shows a bare trace, every frame draws: the static
            // preview and a frame every tenth of the run, through the shared scene onto a Java2D surface.
            val viewed = ReplayModel.build(AnimationSource(layout, ksl.animation.AnimationTraceHeader(), events))
            val builder = SceneBuilder(viewed)
            val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            try {
                val frames = listOf(builder.buildStatic()) + (0..10).map { builder.build(t0 + (t1 - t0) * it / 10.0) }
                for (scene in frames) {
                    if (v.guidePaths.isNotEmpty() && scene.commandsOf("guidePaths").isEmpty()) {
                        problems += "$label: no guide path drawn at ${scene.simTime}"
                    }
                    SceneRenderer.render(scene, Java2dSurface(g, 640.0, 480.0), ViewTransform.fit(scene.worldBounds, 640.0, 480.0))
                }
            } catch (e: Exception) {
                problems += "$label: drawing failed: $e"
            } finally {
                g.dispose()
            }
        }
        if (problems.isNotEmpty()) fail<Unit>(problems.distinct().take(40).joinToString("\n"))
        println("vehicle replay census: transporters=$transporters freePathMovers=$freePathMovers " +
            "loadSamples=$loadsCarried closureSamples=$closures assignmentSamples=$assignments")
        assertTrue(transporters > 0 && freePathMovers > 0 && loadsCarried > 0 && closures > 0 && assignments > 0,
            "the vehicle models exercised every part of the replay")
    }
}
