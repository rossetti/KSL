package ksl.examples.general.vehiclebundle

import ksl.animation.AnimationCapture
import ksl.animation.AnimationEvent
import ksl.animation.CaptureMode
import ksl.animation.CaptureSpec
import ksl.animation.CaptureWindow
import ksl.animation.TraceFileReader
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 *  The contract between KSLCore's vehicle animation events and a renderer, checked on every shipped
 *  vehicle model. A renderer draws a guide path, places every transporter at every instant, shades
 *  closures, ties assignments and carried loads to the vehicle that moves, and does all of that from a
 *  trace alone, including one whose capture window opens mid-run. Each assertion here is something a
 *  study of these traces found missing before R1.7.1; this is what keeps it from going missing again.
 */
class VehicleTraceContractTest {

    private val builders: List<ModelBuilderIfc> = listOf(
        SimpleAgvShopModelBuilder(), GuidePathDisturbancesModelBuilder(), MultiFloorHospitalModelBuilder(),
        TwoLaneWarehouseModelBuilder(), FreePathFleetYardModelBuilder(), PassiveTransporterShopModelBuilder(),
        ActiveFleetShopModelBuilder(), DispatchingRulesRingShopModelBuilder(), PedestrianCrossingModelBuilder(),
        TestAndRepairShopWithGuidedTransportersModelBuilder()
    )

    private fun buildShort(builder: ModelBuilderIfc): Model {
        val m = builder.build(null, null as ExperimentRunParametersIfc?)
        m.numberOfReplications = 1
        m.lengthOfReplicationWarmUp = 0.0
        m.lengthOfReplication = minOf(m.lengthOfReplication, 300.0)
        return m
    }

    private fun trace(builder: ModelBuilderIfc, spec: CaptureSpec = CaptureSpec()): Pair<Model, List<AnimationEvent>> {
        val m = buildShort(builder)
        val file = Files.createTempFile("vehicle-contract", ".atf")
        try {
            val capture = AnimationCapture.toFile(m, file, captureSpec = spec)
            m.simulate()
            capture.close()
            return m to TraceFileReader.readAll(file).second
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** Zone name to a drawable place: a link zone or an intersection, per the documented naming rule. */
    private fun zoneNames(def: AnimationEvent.GuidedPathDefined): Set<String> =
        def.intersections.map { it.name }.toSet() +
            def.links.flatMap { l -> (1..l.numZones).map { "${l.name}.Zone$it" } }

    @Test
    fun everyShippedVehicleModelProducesARenderableTrace() {
        val problems = mutableListOf<String>()
        for (builder in builders) {
            val label = builder::class.simpleName
            val (_, events) = trace(builder)
            val defs = events.filterIsInstance<AnimationEvent.GuidedPathDefined>()
            val zones = defs.flatMap { zoneNames(it) }.toSet()
            val places = defs.flatMap { d -> d.intersections.flatMap { listOf(it.name) + it.aliases } }.toSet()
            val transporters = defs.flatMap { d -> d.transporters.map { it.name } }.toSet()

            // Coordinates: every intersection drawable.
            if (defs.any { d -> d.intersections.any { !it.x.isFinite() || !it.y.isFinite() } }) {
                problems += "$label: a guide path intersection has no position"
            }
            // Placement: every move and every closure resolves against the definition.
            events.filterIsInstance<AnimationEvent.GuidedTransporterMoved>().firstOrNull { it.zoneName !in zones }
                ?.let { problems += "$label: move to unknown zone ${it.zoneName}" }
            events.filterIsInstance<AnimationEvent.GuidedPathClosureChanged>()
                .flatMap { it.zoneNames }.firstOrNull { it !in zones }
                ?.let { problems += "$label: closure of unknown zone $it" }
            // Assignments: tied to the vehicle that moves, and to places on the path.
            for (a in events.filterIsInstance<AnimationEvent.AgvAssignmentMade>()) {
                if (a.bodyName == null) problems += "$label: assignment ${a.taskId} has no body name"
                if (a.networkName != null) {
                    if (a.bodyName !in transporters) problems += "$label: assignment body ${a.bodyName} is not a transporter"
                    if (a.origin !in places || a.destination !in places) {
                        problems += "$label: assignment ${a.origin} -> ${a.destination} is not on the path"
                    }
                }
            }
            // Loads: whenever a transporter moves loaded, something is aboard it by the trace's own account.
            val aboard = HashMap<String, MutableSet<Long>>()
            for (e in events) when (e) {
                is AnimationEvent.VehicleLoadBoarded -> aboard.getOrPut(e.bodyName ?: e.vehicleName) { HashSet() } += e.entityId
                is AnimationEvent.VehicleLoadAlighted -> aboard[e.bodyName ?: e.vehicleName]?.remove(e.entityId)
                is AnimationEvent.GuidedTransporterStateChanged ->
                    if (e.state == "MOVING_LOADED" && aboard[e.transporterName].isNullOrEmpty()) {
                        problems += "$label: ${e.transporterName} moved loaded at ${e.simTime} with nothing aboard in the trace"
                    }
                else -> {}
            }
            // A free-path fleet's vehicles actually move in the trace.
            if (builder is FreePathFleetYardModelBuilder && events.none { it is AnimationEvent.SpatialElementMoved }) {
                problems += "$label: free-path vehicles emitted no movement"
            }
        }
        if (problems.isNotEmpty()) fail<Unit>(problems.distinct().joinToString("\n"))
    }

    @Test
    fun aSelectionOmittingTheVehiclesOmitsTheirEvents() {
        val spec = CaptureSpec(CaptureMode.SELECTED, include = emptyList())
        for (builder in builders) {
            val (_, events) = trace(builder, spec)
            assertTrue(
                events.none {
                    it is AnimationEvent.GuidedPathDefined || it is AnimationEvent.GuidedTransporterMoved ||
                        it is AnimationEvent.AgvAssignmentMade || it is AnimationEvent.SpatialElementMoved
                },
                "${builder::class.simpleName}: an unselected vehicle still reached the trace"
            )
        }
    }

    @Test
    fun aWindowOpeningMidRunStartsWithTheGuidePathAndItsTransporters() {
        for (builder in builders) {
            val length = buildShort(builder).lengthOfReplication
            val start = length / 2.0
            val (_, events) = trace(builder, CaptureSpec(captureWindow = CaptureWindow(start, length)))
            val defs = events.filterIsInstance<AnimationEvent.GuidedPathDefined>()
            val full = trace(builder).second.filterIsInstance<AnimationEvent.GuidedPathDefined>()
            if (full.isEmpty()) continue // a free-path model has no guide path to restate
            assertTrue(defs.isNotEmpty(), "${builder::class.simpleName}: the windowed trace has no guide path")
            val placedAtStart = events.filterIsInstance<AnimationEvent.GuidedTransporterMoved>()
                .filter { it.simTime == start }.map { it.transporterName }.toSet()
            val all = defs.flatMap { d -> d.transporters.map { it.name } }.toSet()
            assertTrue(placedAtStart.containsAll(all),
                "${builder::class.simpleName}: transporters ${all - placedAtStart} not placed at the window start")
        }
    }
}
