package ksl.modeling.guidedpath

import ksl.animation.AnimationCapture
import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationSink
import ksl.animation.CaptureMode
import ksl.animation.CaptureSpec
import ksl.animation.CaptureWindow
import ksl.animation.ElementKind
import ksl.animation.ElementSelector
import ksl.animation.TraceFileReader
import ksl.animation.animationInventory
import ksl.animation.scaffoldLayout
import ksl.animation.validateAgainst
import ksl.modeling.guidedpath.exceptions.GuidedPathNetworkException
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.math.hypot

/**
 *  What the R1.7.1 vehicle animation work (V1, V2, V4, V9, V10, V11, V12, V13, V14) promises about the
 *  guide path's structure and its capture, checked on small networks rather than the shipped models,
 *  which `VehicleTraceContractTest` in KSLExamples covers.
 */
class GuidedPathLayoutAndCaptureTest {

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean get() = true
        override fun emit(event: AnimationEvent) {
            events.add(event)
        }
    }

    /** A three-intersection line with no coordinates: A -10- B -20- C, plus an alias for C. */
    private fun coordinateFreeNetwork(name: String = "Line"): GuidedPathNetwork =
        GuidedPathNetwork.builder(name)
            .link("AB", "A", "B", length = 10.0, zoneLength = 5.0, type = LinkType.BIDIRECTIONAL)
            .link("BC", "B", "C", length = 20.0, zoneLength = 5.0, type = LinkType.BIDIRECTIONAL)
            .station("Dock", "C")
            .build()

    @Test
    fun aNetworkWithoutCoordinatesIsLaidOutFromItsLinkLengths() {
        val net = coordinateFreeNetwork()
        val a = net.intersection("A")!!
        val b = net.intersection("B")!!
        val c = net.intersection("C")!!
        for (i in listOf(a, b, c)) assertTrue(i.x.isFinite() && i.y.isFinite(), "$i has no position")
        assertEquals(10.0, hypot(a.x - b.x, a.y - b.y), 1e-6)
        assertEquals(20.0, hypot(b.x - c.x, b.y - c.y), 1e-6)
        assertTrue(a.x >= 0.0 && a.y >= 0.0, "the first intersection's signs fix the orientation")
    }

    @Test
    fun partialCoordinatesAreRefusedNamingTheMissingOnes() {
        val e = assertThrows(GuidedPathNetworkException::class.java) {
            GuidedPathNetwork.builder("Partial")
                .intersection("A", x = 0.0, y = 0.0)
                .link("AB", "A", "B", length = 10.0, zoneLength = 5.0)
                .build()
        }
        assertTrue(e.message!!.contains("[B]"), e.message)
    }

    @Test
    fun anIntersectionALinkCreatedCanStillBeGivenCoordinates() {
        val net = GuidedPathNetwork.builder("Late")
            .link("AB", "A", "B", length = 10.0, zoneLength = 5.0)
            .intersection("A", x = 0.0, y = 0.0)
            .intersection("B", x = 10.0, y = 0.0)
            .build()
        assertEquals(10.0, net.intersection("B")!!.x, 0.0)
        // Its length is fixed into its zone at creation, so that part still has to come first.
        assertThrows(GuidedPathNetworkException::class.java) {
            GuidedPathNetwork.builder("TooLate")
                .link("AB", "A", "B", length = 10.0, zoneLength = 5.0)
                .intersection("A", length = 2.0, x = 0.0, y = 0.0)
        }
    }

    /** A cart on a coordinate-free line, sent to the aliased dock. */
    private class Line(parent: ModelElement, spaceName: String = "Sys", networkName: String = "Line") :
        ModelElement(parent, "Line_$spaceName") {
        val network = GuidedPathNetwork.builder(networkName)
            .link("AB", "A", "B", length = 10.0, zoneLength = 5.0, type = LinkType.BIDIRECTIONAL)
            .link("BC", "B", "C", length = 20.0, zoneLength = 5.0, type = LinkType.BIDIRECTIONAL)
            .station("Dock", "C")
            .build()
        val system = GuidedPathTransportSystem(this, network, name = spaceName)
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(5.0), 1, EndOfZoneControl(), "Cart_$spaceName"
        )

        override fun initialize() {
            schedule({ _: KSLEvent<Nothing> -> cart.sendTo("C") }, 1.0)
        }
    }

    @Test
    fun theDefinitionCarriesLengthsAliasesTransportersAndItsSpace() {
        val sink = CollectingSink()
        val m = Model("DefinitionContent")
        Line(m)
        m.animationSink = sink
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()
        val def = sink.events.filterIsInstance<AnimationEvent.GuidedPathDefined>().single()
        assertEquals("Sys", def.spaceName)
        assertEquals(10.0, def.links.single { it.name == "AB" }.length, 0.0)
        assertEquals(listOf("Dock"), def.intersections.single { it.name == "C" }.aliases)
        val t = def.transporters.single()
        assertEquals("Cart_Sys", t.name)
        assertEquals(1, t.lengthInZones)
        assertEquals(1, t.loadCapacity)
        assertTrue(def.intersections.all { it.x.isFinite() && it.y.isFinite() })
        // Nothing about a transporter is reported before the path it stands on.
        val first = sink.events.indexOfFirst { it is AnimationEvent.GuidedTransporterStateChanged }
        assertTrue(first > sink.events.indexOf(def), "a transporter state preceded its guide path")
        assertTrue(sink.events.filterIsInstance<AnimationEvent.GuidedTransporterStateChanged>()
            .all { it.spaceName == "Sys" })
    }

    @Test
    fun twoNetworksWithOneNameStayDistinctBySpace() {
        val sink = CollectingSink()
        val m = Model("SameName")
        Line(m, spaceName = "Left", networkName = "Floor")
        Line(m, spaceName = "Right", networkName = "Floor")
        m.animationSink = sink
        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()
        val spaces = sink.events.filterIsInstance<AnimationEvent.GuidedPathDefined>().map { it.spaceName }.toSet()
        assertEquals(setOf("Left", "Right"), spaces)
    }

    @Test
    fun theInventoryListsGuidePathsAndTransportersApartFromResources() {
        val m = Model("Inventory")
        Line(m)
        val inv = m.animationInventory()
        assertEquals(listOf("Sys"), inv.guidedPaths.map { it.spaceName })
        assertEquals(listOf("Cart_Sys"), inv.guidedTransporters)
        assertFalse("Cart_Sys" in inv.resources, "a transporter is drawn on its path, not as a resource box")
        assertTrue("Dock" in inv.locations)
        assertEquals(listOf("Cart_Sys"), inv.namesOf(ElementKind.GUIDED_TRANSPORTER))
    }

    @Test
    fun theScaffoldPlacesTheGuidePathAndValidates() {
        val m = Model("Scaffold")
        Line(m)
        val layout = m.scaffoldLayout()
        assertEquals(listOf("Sys"), layout.guidedPaths.map { it.spaceName })
        assertEquals(listOf("Cart_Sys"), layout.guidedTransporters.map { it.name })
        assertTrue(layout.resources.none { it.resourceName == "Cart_Sys" })
        assertTrue(layout.validateAgainst(m).isValid, layout.validateAgainst(m).toString())
        // And the new sections survive both codecs.
        assertEquals(layout, AnimationLayout.fromJson(layout.toJson()))
        assertEquals(layout, AnimationLayout.fromToml(layout.toToml()))
    }

    private fun capture(spec: CaptureSpec, length: Double = 20.0): List<AnimationEvent> {
        val m = Model("Capture")
        Line(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = length
        val trace = Files.createTempFile("guided-capture", ".atf")
        try {
            val capture = AnimationCapture.toFile(m, trace, captureSpec = spec)
            m.simulate()
            capture.close()
            return TraceFileReader.readAll(trace).second
        } finally {
            Files.deleteIfExists(trace)
        }
    }

    @Test
    fun aFleetCountIsAssignedOnlyWhenItChanges() {
        // refreshFleetCounts runs after every transporter event. Reassigning an unchanged count recorded an
        // observation, notified observers and emitted each time, which was most of an animated vehicle
        // model's trace. A time-weighted response still closes its own interval at initialize and at the
        // end of the replication by reassigning its value; those are its own, at the replication's ends.
        val m = Model("FleetCounts")
        Line(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        val repeats = mutableListOf<String>()
        val names = listOf(
            "Sys:NumTransportersMoving", "Sys:NumTransportersBlocked", "Sys:NumTransportersIdle", "Sys:ZoneUtilization"
        )
        for (name in names) {
            val response = m.getModelElement(name) as ksl.modeling.variable.TWResponse
            response.attachModelElementObserver(object : ksl.observers.ModelElementObserver() {
                override fun update(modelElement: ksl.simulation.ModelElement) {
                    val t = modelElement.time
                    if (t > 0.0 && t < 20.0 && response.value == response.previousValue) {
                        repeats += "$name reassigned ${response.value} at $t"
                    }
                }
            })
        }
        m.simulate()
        assertTrue(repeats.isEmpty(), repeats.joinToString("\n"))
    }

    @Test
    fun aTraceDoesNotRepeatAnUnchangedTimeWeightedValue() {
        // Including the reassignments a time-weighted response makes of itself at initialize and at the
        // end of the replication, which close its statistic's intervals but draw nothing new.
        val observed = capture(CaptureSpec(), length = 20.0).filterIsInstance<AnimationEvent.ResponseObserved>()
            .filter { it.responseName.startsWith("Sys:NumTransporters") || it.responseName == "Sys:ZoneUtilization" }
        assertTrue(observed.isNotEmpty(), "the fleet counts are captured")
        for ((name, series) in observed.groupBy { it.responseName }) {
            series.zipWithNext().firstOrNull { (a, b) -> a.value == b.value }?.let { (a, b) ->
                fail<Unit>("$name was emitted again at ${b.simTime} with the value it already had at ${a.simTime}")
            }
        }
    }

    @Test
    fun theGuidePathsBookkeepingCountsAreLeftOutUnlessIncluded() {
        fun counted(spec: CaptureSpec) = capture(spec, length = 20.0).filterIsInstance<AnimationEvent.ResponseObserved>()
            .map { it.responseName }.toSet()
        val everything = counted(CaptureSpec())
        assertFalse("Sys:NumZoneTraversals" in everything || "Sys:NumEventsScheduled" in everything,
            "a capture of everything leaves the bookkeeping counts out")
        assertTrue("Sys:NumTransportersMoving" in everything, "and keeps the guide path's other statistics")
        val asked = counted(CaptureSpec(include = listOf(ElementSelector(ElementKind.COUNTER, "Sys:NumZoneTraversals"))))
        assertTrue("Sys:NumZoneTraversals" in asked, "including one by name puts it back")
        assertFalse("Sys:NumEventsScheduled" in asked)
        val windowed = counted(CaptureSpec(captureWindow = CaptureWindow(10.0, 20.0)))
        assertFalse("Sys:NumZoneTraversals" in windowed, "and a window's opening restatement follows the same rule")
        val m = Model("Inventory").also { Line(it) }
        assertEquals(setOf("Sys:NumZoneTraversals", "Sys:NumEventsScheduled"),
            m.animationInventory().notCapturedByDefault.toSet(), "the inventory says which, for the Capture tab")
    }

    @Test
    fun aSelectionThatOmitsTheGuidePathOmitsItsEvents() {
        val events = capture(CaptureSpec(CaptureMode.SELECTED, include = listOf(ElementSelector(ElementKind.RESPONSE, "Cart_Sys:NumTransports"))))
        assertTrue(events.none { it is AnimationEvent.GuidedPathDefined || it is AnimationEvent.GuidedTransporterMoved })
    }

    @Test
    fun anExcludedTransporterIsOmittedWhileItsPathIsKept() {
        val events = capture(CaptureSpec(exclude = listOf(ElementSelector(ElementKind.GUIDED_TRANSPORTER, "Cart_Sys"))))
        assertTrue(events.any { it is AnimationEvent.GuidedPathDefined })
        assertTrue(events.none { it is AnimationEvent.GuidedTransporterMoved })
    }

    @Test
    fun aWindowOpeningMidRunStillHasThePathAndTheCart() {
        val events = capture(CaptureSpec(captureWindow = CaptureWindow(3.0, 20.0)))
        val def = events.indexOfFirst { it is AnimationEvent.GuidedPathDefined }
        assertTrue(def >= 0, "the windowed trace lost its guide path")
        assertTrue(events.drop(def).any { it is AnimationEvent.GuidedTransporterMoved && it.simTime == 3.0 },
            "the cart was not placed at the window start")
    }
}
