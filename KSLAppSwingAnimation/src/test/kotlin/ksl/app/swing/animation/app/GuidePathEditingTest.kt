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

package ksl.app.swing.animation.app

import ksl.animation.AnimationLayout
import ksl.animation.ElementKind
import ksl.animation.GuidedPathLayoutElement
import ksl.animation.GuidedTransporterLayoutElement
import ksl.animation.animationInventory
import ksl.app.animation.replay.suggestedGuidedPathLayout
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Authoring a vehicle model in the Animation app: the capture tabs offer its guide path and transporter, the
 * layout editor places and styles them as a whole, and the pre-run preview draws the guide path from the model.
 */
class GuidePathEditingTest {

    /** A two-floor line: A and B on the ground, C a floor up and home to the cart. */
    private class Lift(parent: ModelElement) : ModelElement(parent, "TwoFloorLine") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Net")
            .intersection("A", x = 0.0, y = 0.0)
            .intersection("B", x = 40.0, y = 0.0)
            .intersection("C", x = 40.0, y = 0.0, z = 4.0)
            .link("AB", "A", "B", length = 40.0, zoneLength = 10.0, type = LinkType.BIDIRECTIONAL)
            .link("BC", "B", "C", length = 4.0, zoneLength = 4.0, type = LinkType.BIDIRECTIONAL)
            .station("Ward", "C")
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Floors")
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(5.0), 1, EndOfZoneControl(), "Cart")
            .also { it.homeBase = "Ward" }
    }

    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
            Model("Lift").also { Lift(it) }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result.getOrThrow()
    }

    @Test
    fun guidePathAndTransporterStylingIsAddedReplacedAndRemovedByName() {
        val a = AnimationLayout()
            .withGuidedPathLayout(GuidedPathLayoutElement("Floors", linkColor = "#111111"))
            .withGuidedPathLayout(GuidedPathLayoutElement("Floors", linkColor = "#222222"))
            .withGuidedTransporterLayout(GuidedTransporterLayoutElement("Cart", size = 3.0))
        assertEquals(listOf("#222222"), a.guidedPaths.map { it.linkColor }, "replaced, not duplicated")
        assertEquals(3.0, a.guidedTransporters.single().size)
        val b = a.withGuidedPathRemoved("Floors").withGuidedTransporterRemoved("Cart")
        assertTrue(b.guidedPaths.isEmpty() && b.guidedTransporters.isEmpty())
    }

    @Test
    fun aClimbingPathsSuggestedStylingSeparatesItsFloors() {
        val info = Model("Lift").also { Lift(it) }.animationInventory().guidedPaths.single()
        val suggested = suggestedGuidedPathLayout(info)
        assertEquals("Floors", suggested.spaceName)
        assertNotNull(suggested.floorOffsetPerZ, "two floors, so a separation is suggested")
        val flat = info.copy(intersections = info.intersections.map { it.copy(z = 0.0) })
        assertNull(suggestedGuidedPathLayout(flat).floorOffsetPerZ, "a flat path is left alone")
    }

    @Test
    fun theCaptureTabsOfferTheGuidePathAndItsTransporter() {
        val controller = AnimationAppController("Lift", builder)
        try {
            val shown = onEdt {
                val panel = CapturePanel(controller)
                panel.namesShownForTest(ElementKind.GUIDED_PATH) to panel.namesShownForTest(ElementKind.GUIDED_TRANSPORTER)
            }
            assertEquals(listOf("Floors"), shown.first)
            assertEquals(listOf("Cart"), shown.second)
            val counter = onEdt { CapturePanel(controller).shownStateForTest(ksl.animation.ElementKind.COUNTER, "Floors:NumZoneTraversals") }
            assertEquals("Default (off)", counter, "the guide path's bookkeeping count reads as off by default")
        } finally {
            controller.close()
        }
    }

    @Test
    fun thePreRunPreviewDrawsTheGuidePathWithTheCartAtHome() {
        val controller = AnimationAppController("Lift", builder)
        try {
            val preview = onEdt {
                controller.setGuidedPathLayout(GuidedPathLayoutElement("Floors"))
                LayoutPanel(controller).previewReplayForTest()
            }
            val vehicles = assertNotNull(preview).vehicles
            assertEquals(setOf("Floors"), vehicles.guidePaths.keys)
            val c = vehicles.guidePaths.getValue("Floors").intersectionPoint("C")
            assertEquals(c, vehicles.transporterPositionAt("Cart", 0.0), "the cart waits at its home, Ward, which is C")
        } finally {
            controller.close()
        }
    }
}
