package ksl.app.swing.animation.app

import ksl.animation.AnchorKind
import ksl.animation.AnchorRef
import ksl.animation.AnimationLayout
import ksl.animation.ElementKind
import ksl.animation.LayoutPoint
import ksl.animation.NetworkStationLayoutElement
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Path
import javax.swing.SwingUtilities
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage 3: the Layout tab is a faithful, headless view over the controller's layout mutators. Interactions
 * run on the EDT (as in the real app) so they serialize with the panel's own `layout` subscription.
 */
class LayoutPanelTest {

    @TempDir
    lateinit var tempRoot: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("TRLayoutUi").also { TestAndRepairShopWithMovableResources(it, "TR") }
    }

    private fun controller() = AnimationAppController("Anim", builder)

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `editor lists carry the model inventory names`() {
        val c = controller()
        try {
            val shown = onEdt { LayoutPanel(c).namesShownForTest(ElementKind.RESOURCE) }
            assertEquals(c.inventory.namesOf(ElementKind.RESOURCE), shown)
            assertContains(shown, "Test1")
        } finally { c.close() }
    }

    @Test
    fun `add then move routes to the controller and tags the cell, with a live preview`() {
        val c = controller()
        try {
            val result = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.RESOURCE, "Test1")
                panel.moveForTest(ElementKind.RESOURCE, "Test1", 321.0, 123.0)
                Triple(
                    c.layout.value!!.isPlaced(ElementKind.RESOURCE, "Test1"),
                    c.layout.value!!.positionOf(ElementKind.RESOURCE, "Test1"),
                    panel.cellsForTest(ElementKind.RESOURCE, "Test1") to panel.previewHasModelForTest()
                )
            }
            assertTrue(result.first, "element placed via the panel")
            assertEquals(321.0, result.second?.x)
            assertEquals(123.0, result.second?.y)
            // The table cell shows Placed=true and the live coordinates.
            assertTrue(result.third.first.first, "Placed checkbox reflects the placement")
            assertEquals("321", result.third.first.second)
            assertEquals("123", result.third.first.third)
            assertTrue(result.third.second, "the preview builds a model once a layout exists")
        } finally { c.close() }
    }

    @Test
    fun `coordinates display rounded, not with float noise`() {
        val c = controller()
        try {
            val xText = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.RESOURCE, "Test1")
                panel.moveForTest(ElementKind.RESOURCE, "Test1", 80.00000000000001, 150.0)
                panel.cellsForTest(ElementKind.RESOURCE, "Test1").second
            }
            assertEquals("80", xText, "float noise is rounded away in the X cell")
        } finally { c.close() }
    }

    @Test
    fun `queue properties set direction, spacing and max shown`() {
        val c = controller()
        try {
            val q = c.inventory.namesOf(ElementKind.QUEUE).first()
            val element = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.QUEUE, q)
                panel.setQueuePropsForTest(q, dir = 90.0, spacing = 18.0, maxShown = 12)
                c.layout.value!!.queues.first { it.queueName == q }
            }
            assertEquals(90.0, element.growthDegrees)
            assertEquals(18.0, element.spacing)
            assertEquals(12, element.maxShown)
        } finally { c.close() }
    }

    @Test
    fun `placed elements get grab handles on the preview`() {
        val c = controller()
        try {
            val handles = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.QUEUE, c.inventory.namesOf(ElementKind.QUEUE).first())
                panel.addForTest(ElementKind.RESOURCE, "Test1")
                panel.handleCountForTest()
            }
            assertTrue(handles >= 2, "a handle is drawn at each placed element, got $handles")
        } finally { c.close() }
    }

    @Test
    fun `pan toggle switches the canvas between move and pan`() {
        val c = controller()
        try {
            val states = onEdt {
                val panel = LayoutPanel(c)
                val canvas = panel.previewCanvasForTest()
                val editMode = canvas.panEnabled // false: drag moves elements
                panel.setPanModeForTest(true)
                val panOn = canvas.panEnabled
                panel.setPanModeForTest(false)
                Triple(editMode, panOn, canvas.panEnabled)
            }
            assertFalse(states.first, "edit mode: drag moves elements (pan off)")
            assertTrue(states.second, "pan on")
            assertFalse(states.third, "pan off again")
        } finally { c.close() }
    }

    @Test
    fun `place all adds every element of a kind`() {
        val c = controller()
        try {
            val allPlaced = onEdt {
                val panel = LayoutPanel(c)
                panel.placeAllForTest(ElementKind.RESOURCE)
                c.inventory.namesOf(ElementKind.RESOURCE).all { c.layout.value!!.isPlaced(ElementKind.RESOURCE, it) }
            }
            assertTrue(allPlaced, "Place all placed every resource")
        } finally { c.close() }
    }

    @Test
    fun `place selected bulk-adds multiple elements`() {
        val c = controller()
        try {
            val placed = onEdt {
                val panel = LayoutPanel(c)
                val names = c.inventory.namesOf(ElementKind.RESOURCE).take(3)
                panel.placeManyForTest(ElementKind.RESOURCE, *names.toTypedArray())
                names.all { c.layout.value!!.isPlaced(ElementKind.RESOURCE, it) }
            }
            assertTrue(placed, "all selected resources were placed in one action")
        } finally { c.close() }
    }

    @Test
    fun `remove clears the element`() {
        val c = controller()
        try {
            val placedAfterRemove = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.QUEUE, c.inventory.namesOf(ElementKind.QUEUE).first())
                panel.removeForTest(ElementKind.QUEUE, c.inventory.namesOf(ElementKind.QUEUE).first())
                c.layout.value!!.queues.isNotEmpty()
            }
            assertFalse(placedAfterRemove, "the queue was removed")
        } finally { c.close() }
    }

    @Test
    fun `canvas resize routes to the controller`() {
        val c = controller()
        try {
            val size = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.RESOURCE, "Test1") // ensure a layout exists
                panel.resizeCanvasForTest(1357.0, 642.0)
                c.layout.value!!.width to c.layout.value!!.height
            }
            assertEquals(1357.0, size.first)
            assertEquals(642.0, size.second)
        } finally { c.close() }
    }

    @Test
    fun `click-to-place arms a kind and places it at the clicked world point`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                panel.armPlaceForTest(ElementKind.RESOURCE, "Test1")
                val armed = panel.isArmedForTest()
                panel.placeAtForTest(250.0, 175.0)   // simulate the canvas click
                arrayOf(
                    armed,
                    c.layout.value!!.isPlaced(ElementKind.RESOURCE, "Test1"),
                    panel.isArmedForTest()
                ) to c.layout.value!!.positionOf(ElementKind.RESOURCE, "Test1")
            }
            assertTrue(r.first[0], "armed after choosing a tool")
            assertTrue(r.first[1], "placed after the click")
            assertFalse(r.first[2], "disarmed after placing")
            assertEquals(250.0, r.second?.x)
            assertEquals(175.0, r.second?.y)
        } finally { c.close() }
    }

    @Test
    fun `arming a tool highlights it and cancel clears the armed state`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                panel.armPlaceForTest(ElementKind.RESOURCE, "Test1")
                val armed = panel.armedKindForTest() to panel.toolHighlightedForTest(ElementKind.RESOURCE)
                panel.cancelPlaceForTest()
                Triple(armed.first, armed.second, panel.armedKindForTest() to panel.toolHighlightedForTest(ElementKind.RESOURCE))
            }
            assertEquals(ElementKind.RESOURCE, r.first, "the armed kind is tracked")
            assertTrue(r.second, "the armed tool button is highlighted")
            assertEquals(null, r.third.first, "cancel clears the armed kind")
            assertFalse(r.third.second, "cancel removes the highlight")
        } finally { c.close() }
    }

    @Test
    fun `queue two-click flow sets head position then derives direction from the tail`() {
        val c = controller()
        try {
            val q = c.inventory.namesOf(ElementKind.QUEUE).first()
            val r = onEdt {
                val panel = LayoutPanel(c)
                panel.armQueueForTest(q, spacing = 18.0, maxShown = 12)
                panel.clickQueueForTest(300.0, 180.0)                 // HEAD
                val afterHead = c.layout.value!!.positionOf(ElementKind.QUEUE, q) to panel.isQueueArmedForTest()
                panel.clickQueueForTest(300.0, 280.0)                 // TAIL straight down ⇒ 90°
                val q2 = c.layout.value!!.queues.first { it.queueName == q }
                Triple(afterHead, q2, panel.isQueueArmedForTest())
            }
            assertEquals(300.0, r.first.first?.x, "head x")
            assertEquals(180.0, r.first.first?.y, "head y")
            assertTrue(r.first.second, "still armed after the head click (awaiting tail)")
            assertEquals(90.0, r.second.growthDegrees, "tail straight down ⇒ 90° growth")
            assertEquals(18.0, r.second.spacing, "spacing from the dialog")
            assertEquals(12, r.second.maxShown, "max shown from the dialog")
            assertFalse(r.third, "disarmed after the tail click")
        } finally { c.close() }
    }

    @Test
    fun `path tool routes a functional path between anchors with waypoints`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                // Place two stations (the Station tool would; this model has none in inventory).
                c.placeLayoutElement(ElementKind.STATION, "S1", 100.0, 120.0)
                c.placeLayoutElement(ElementKind.STATION, "S2", 300.0, 120.0)
                panel.armPathForTest("Route1")
                val armed = panel.isPathArmedForTest()
                panel.setPathFromForTest(AnchorRef(AnchorKind.NETWORK_STATION, "S1"))
                panel.addPathWaypointForTest(200.0, 60.0)
                panel.finishPathForTest(AnchorRef(AnchorKind.NETWORK_STATION, "S2"))
                Triple(armed, c.layout.value!!.paths.firstOrNull { it.name == "Route1" }, panel.isPathArmedForTest())
            }
            assertTrue(r.first, "armed after choosing the path tool")
            val path = assertNotNull(r.second)
            assertEquals(AnchorRef(AnchorKind.NETWORK_STATION, "S1"), path.from, "from anchor persisted")
            assertEquals(AnchorRef(AnchorKind.NETWORK_STATION, "S2"), path.to, "to anchor persisted")
            assertEquals(1, path.points.size, "the one dropped waypoint is stored")
            assertFalse(r.third, "disarmed after finishing")
        } finally { c.close() }
    }

    @Test
    fun `path tool auto-derives the name from the endpoints and disambiguates duplicates (H2)`() {
        val c = controller()
        try {
            val names = onEdt {
                val panel = LayoutPanel(c)
                c.placeLayoutElement(ElementKind.STATION, "Enter", 100.0, 120.0)
                c.placeLayoutElement(ElementKind.STATION, "Station1", 300.0, 120.0)
                // First A→B: name derived as "Enter → Station1" (no prompt, no explicit name).
                panel.armPathAutoForTest()
                panel.setPathFromForTest(AnchorRef(AnchorKind.NETWORK_STATION, "Enter"))
                panel.finishPathForTest(AnchorRef(AnchorKind.NETWORK_STATION, "Station1"))
                // A second A→B must not clobber the first (withFunctionalPath replaces by name) — disambiguated.
                panel.armPathAutoForTest()
                panel.setPathFromForTest(AnchorRef(AnchorKind.NETWORK_STATION, "Enter"))
                panel.finishPathForTest(AnchorRef(AnchorKind.NETWORK_STATION, "Station1"))
                c.layout.value!!.paths.map { it.name }
            }
            assertTrue("Enter → Station1" in names, "name derived from the endpoints: $names")
            assertTrue("Enter → Station1 (2)" in names, "a second same-endpoint path is disambiguated: $names")
        } finally { c.close() }
    }

    @Test
    fun `path preview shows the start anchor then waypoints while arming, and clears on finish (H1)`() {
        val c = controller()
        try {
            val (afterFrom, afterWp, afterFinish) = onEdt {
                val panel = LayoutPanel(c)
                c.placeLayoutElement(ElementKind.STATION, "S1", 100.0, 120.0)
                c.placeLayoutElement(ElementKind.STATION, "S2", 300.0, 120.0)
                panel.armPathAutoForTest()
                panel.setPathFromForTest(AnchorRef(AnchorKind.NETWORK_STATION, "S1"))
                val a = panel.previewCanvasForTest().pathPreviewScreen.size
                panel.addPathWaypointForTest(200.0, 60.0)
                val b = panel.previewCanvasForTest().pathPreviewScreen.size
                panel.finishPathForTest(AnchorRef(AnchorKind.NETWORK_STATION, "S2"))
                Triple(a, b, panel.previewCanvasForTest().pathPreviewScreen.size)
            }
            assertEquals(1, afterFrom, "preview shows the chosen start anchor")
            assertEquals(2, afterWp, "preview adds the dropped waypoint")
            assertEquals(0, afterFinish, "preview cleared when the tool disarms")
        } finally { c.close() }
    }

    @Test
    fun `background tool adds an image spanning the dragged rectangle`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout() // ensure a layout exists to receive the background
                panel.armBackgroundForTest("/tmp/floor.png")
                val armed = panel.isBackgroundArmedForTest()
                panel.dragBackgroundRectForTest(50.0, 60.0, 250.0, 260.0)
                Triple(armed, c.layout.value!!.background.lastOrNull(), panel.isBackgroundArmedForTest())
            }
            assertTrue(r.first, "armed after choosing the background tool")
            val bg = r.second
            assertEquals("/tmp/floor.png", bg?.imageRef)
            assertEquals(50.0, bg?.points?.first()?.x, "rectangle min corner x")
            assertEquals(260.0, bg?.points?.get(1)?.y, "rectangle max corner y")
            assertFalse(r.third, "disarmed after the drag")
        } finally { c.close() }
    }

    @Test
    fun `text tool places a text annotation`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                panel.clickShapeTextForTest(2.0, 2.0, "Exit", "#000000")
                val armedAfter = panel.isShapeArmedForTest()
                c.layout.value!!.background to armedAfter
            }
            val bg = r.first
            assertEquals(1, bg.size, "the text annotation was added")
            val text = bg.first { it.kind == ksl.animation.BackgroundKind.TEXT }
            assertEquals("Exit", text.text); assertEquals(2.0, text.points.first().x)
            assertFalse(r.second, "disarmed after placing")
        } finally { c.close() }
    }

    @Test
    fun `resource editor applies and clears per-state images`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.RESOURCE, "Test1")
                panel.setResourceImagesForTest("Test1", "idle.png", "busy.png", null, null)
                val set = c.layout.value!!.resources.first { it.resourceName == "Test1" }
                panel.setResourceImagesForTest("Test1", null, null, null, null) // clear
                val cleared = c.layout.value!!.resources.first { it.resourceName == "Test1" }
                set to cleared
            }
            assertEquals("idle.png", r.first.idleImage)
            assertEquals("busy.png", r.first.busyImage)
            assertEquals(null, r.first.failedImage, "states left unset stay null")
            assertEquals(null, r.second.idleImage, "clearing removes the image (color fallback)")
        } finally { c.close() }
    }

    @Test
    fun `object style with an image uses the IMAGE shape and stores the ref`() {
        val c = controller()
        try {
            val style = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                panel.addObjectStyleWithImageForTest("Part", "/tmp/part.png")
                c.layout.value!!.objectClasses.firstOrNull { it.typeName == "Part" }
            }
            assertEquals("/tmp/part.png", style?.imageRef)
            assertEquals(ksl.animation.LayoutShape.IMAGE, style?.shape, "choosing an image switches the glyph to IMAGE")
        } finally { c.close() }
    }

    @Test
    fun `selecting an element shows a highlight ring that clears when it is removed`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.RESOURCE, "Test1")
                panel.selectForTest(ElementKind.RESOURCE, "Test1")
                val selected = panel.selectedNameForTest() to panel.selectionRingShownForTest()
                panel.removeForTest(ElementKind.RESOURCE, "Test1") // recomputes the ring on refresh
                selected to panel.selectionRingShownForTest()
            }
            assertEquals("Test1", r.first.first, "selection tracks the clicked element")
            assertTrue(r.first.second, "a highlight ring is shown for the selection")
            assertFalse(r.second, "the ring clears once the element is removed")
        } finally { c.close() }
    }

    @Test
    fun `delete key removes the selected element and clears the selection`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.RESOURCE, "Test1")
                panel.selectForTest(ElementKind.RESOURCE, "Test1")
                panel.removeSelectedForTest()
                c.layout.value!!.isPlaced(ElementKind.RESOURCE, "Test1") to panel.selectedNameForTest()
            }
            assertFalse(r.first, "the selected element was removed")
            assertEquals(null, r.second, "selection cleared after delete")
        } finally { c.close() }
    }

    @Test
    fun `spatial locations are placeable and editable as their own kind (Phase 6)`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                val locs = c.inventory.locations
                val loc = locs.first()
                val shown = panel.namesShownForTest(ElementKind.LOCATION) // locations have their own editor now
                panel.addForTest(ElementKind.LOCATION, loc)               // place a location (its own glyph)
                panel.moveForTest(ElementKind.LOCATION, loc, 123.0, 45.0)
                arrayOf(locs.isNotEmpty(), loc in shown, c.layout.value!!.isPlaced(ElementKind.LOCATION, loc)) to
                    c.layout.value!!.positionOf(ElementKind.LOCATION, loc)
            }
            assertTrue(r.first[0], "the model exposes spatial locations")
            assertTrue(r.first[1], "locations appear in the Location editor")
            assertTrue(r.first[2], "a location can be placed")
            assertEquals(123.0, r.second?.x); assertEquals(45.0, r.second?.y)
        } finally { c.close() }
    }

    @Test
    fun `loadLayout migrates a legacy location-saved-as-station into locations (Phase 7)`() {
        val c = controller()
        try {
            val locName = c.inventory.locations.first()
            // A legacy layout that stored this location as a network station.
            val legacy = AnimationLayout(stations = listOf(NetworkStationLayoutElement(locName, LayoutPoint(7.0, 8.0))))
            val file = tempRoot.resolve("legacy.lay.json")
            java.nio.file.Files.writeString(file, legacy.toJson())
            onEdt { c.loadLayout(file) }
            val layout = c.layout.value!!
            assertTrue(layout.locations.any { it.locationName == locName }, "the location-named station migrated to locations")
            assertFalse(layout.stations.any { it.stationName == locName }, "it is no longer a station")
            assertEquals(7.0, layout.positionOf(ElementKind.LOCATION, locName)?.x, "position preserved through migration")
        } finally { c.close() }
    }

    @Test
    fun `dragging a queue's rotation grip sets its growth direction`() {
        val c = controller()
        try {
            val q = c.inventory.namesOf(ElementKind.QUEUE).first()
            val deg = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.QUEUE, q)
                panel.moveForTest(ElementKind.QUEUE, q, 100.0, 100.0) // head at (100,100)
                panel.setQueuePropsForTest(q, dir = 0.0, spacing = 12.0, maxShown = 10)
                panel.queueGripForTest(q)!! // grip somewhere along 0° initially
                panel.rotateQueueToForTest(q, 100.0, 220.0) // drag tail straight down ⇒ 90°
                c.layout.value!!.queues.first { it.queueName == q }.growthDegrees
            }
            assertEquals(90.0, deg, "tail dragged straight down ⇒ 90° growth")
        } finally { c.close() }
    }

    @Test
    fun `marquee selects elements in a box and a group drag moves them together`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                // Two resources inside the box, one far outside.
                panel.addForTest(ElementKind.RESOURCE, "Test1"); panel.moveForTest(ElementKind.RESOURCE, "Test1", 100.0, 100.0)
                panel.addForTest(ElementKind.RESOURCE, "Test2"); panel.moveForTest(ElementKind.RESOURCE, "Test2", 150.0, 120.0)
                panel.addForTest(ElementKind.RESOURCE, "Test3"); panel.moveForTest(ElementKind.RESOURCE, "Test3", 900.0, 900.0)
                panel.marqueeSelectForTest(50.0, 50.0, 200.0, 200.0)
                val selCount = panel.selectionForTest().size
                panel.moveSelectionForTest(10.0, 20.0) // group move
                arrayOf(selCount, panel.selectionRingCountForTest()) to Triple(
                    c.layout.value!!.positionOf(ElementKind.RESOURCE, "Test1"),
                    c.layout.value!!.positionOf(ElementKind.RESOURCE, "Test2"),
                    c.layout.value!!.positionOf(ElementKind.RESOURCE, "Test3")
                )
            }
            assertEquals(2, r.first[0], "marquee selected the two elements inside the box, not the far one")
            assertEquals(2, r.first[1], "a highlight ring per selected element")
            assertEquals(110.0, r.second.first?.x); assertEquals(120.0, r.second.first?.y) // Test1 moved +10,+20
            assertEquals(160.0, r.second.second?.x)                                          // Test2 moved +10
            assertEquals(900.0, r.second.third?.x, "the unselected element did not move")
        } finally { c.close() }
    }

    @Test
    fun `element labels can be retitled, offset, and hidden`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.RESOURCE, "Test1")
                panel.setElementLabelForTest(ElementKind.RESOURCE, "Test1", "Worker A", 20.0, -30.0, true)
                val set = c.layout.value!!.labelFor(ElementKind.RESOURCE, "Test1")
                panel.setElementLabelForTest(ElementKind.RESOURCE, "Test1", null, 0.0, -12.0, false)
                set to c.layout.value!!.labelFor(ElementKind.RESOURCE, "Test1")
            }
            assertEquals("Worker A", r.first?.text, "label retitled")
            assertEquals(20.0, r.first?.dx); assertEquals(-30.0, r.first?.dy, "label offset stored")
            assertFalse(r.second!!.visible, "label can be hidden")
            assertEquals(null, r.second?.text, "blank text clears the override (falls back to the name)")
        } finally { c.close() }
    }

    @Test
    fun `editor-placed queues default to 180 degrees and max shown 10`() {
        val c = controller()
        try {
            val q = c.inventory.namesOf(ElementKind.QUEUE).first()
            val el = onEdt {
                LayoutPanel(c)
                c.newBlankLayout(); c.addLayoutElement(ElementKind.QUEUE, q)
                c.layout.value!!.queues.first { it.queueName == q }
            }
            assertEquals(180.0, el.growthDegrees, "default orientation is tail---head (180)")
            assertEquals(10, el.maxShown, "default max shown is 10")
        } finally { c.close() }
    }

    @Test
    fun `dragging the queue tail toward the head reduces max shown`() {
        val c = controller()
        try {
            val q = c.inventory.namesOf(ElementKind.QUEUE).first()
            val maxShown = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.QUEUE, q)
                panel.moveForTest(ElementKind.QUEUE, q, 100.0, 100.0)
                panel.setQueuePropsForTest(q, dir = 0.0, spacing = 12.0, maxShown = 20)
                // Pull the tail to (160,100): distance 60 / spacing 12 = 5 members shown.
                panel.rotateQueueToForTest(q, 160.0, 100.0)
                c.layout.value!!.queues.first { it.queueName == q }.maxShown
            }
            assertEquals(5, maxShown, "tail distance / spacing sets max shown")
        } finally { c.close() }
    }

    @Test
    fun `dragging a value text grip moves only the value annotation`() {
        val c = controller()
        try {
            val q = c.inventory.namesOf(ElementKind.QUEUE).first()
            val lbl = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.QUEUE, q)
                panel.setElementLabelForTest(ElementKind.QUEUE, q, "Intake", 5.0, -10.0, true) // name offset 5,-10
                panel.dragLabelForTest(ElementKind.QUEUE, q, isValue = true, dx = 40.0, dy = 30.0) // move the value text
                c.layout.value!!.labelFor(ElementKind.QUEUE, q)
            }
            assertEquals(40.0, lbl!!.valueDx); assertEquals(30.0, lbl.valueDy, "value text moved to the dragged offset")
            assertEquals(5.0, lbl.dx); assertEquals(-10.0, lbl.dy, "the name label offset is untouched")
            assertEquals("Intake", lbl.text, "the name text override is preserved")
        } finally { c.close() }
    }

    @Test
    fun `Save As base name strips the layout extension once (no double lay-toml)`() {
        val c = controller()
        try {
            onEdt {
                val panel = LayoutPanel(c)
                assertEquals("foo", panel.stripLayoutExtForTest("foo.lay.toml"), "the .lay.toml extension is stripped")
                assertEquals("foo", panel.stripLayoutExtForTest("foo.lay.json"))
                assertEquals("foo", panel.stripLayoutExtForTest("foo"), "a bare base name is unchanged")
                assertEquals("foo", panel.stripLayoutExtForTest("foo.lay.toml.lay.toml"), "an accidental double is undone")
            }
        } finally { c.close() }
    }

    @Test
    fun `the layout editor legend lists object classes and hints that agents render in Replay (item 7)`() {
        val c = controller()
        try {
            onEdt {
                val panel = LayoutPanel(c)
                assertEquals(" ", panel.legendTextForTest(emptyList()), "no legend when there are no object classes")
                val legend = panel.legendTextForTest(listOf(ksl.animation.ObjectClassDefinition("Boid", color = "#1f77b4")))
                assertTrue(legend.contains("Boid"), "the legend names the object class")
                assertTrue(legend.contains("Replay"), "the legend hints that agents render during Replay")
            }
        } finally { c.close() }
    }

    @Test
    fun `the layout title can be set and cleared from the GUI`() {
        val c = controller()
        try {
            onEdt {
                LayoutPanel(c)
                c.newBlankLayout()
                c.setLayoutTitle("My Animation")
                assertEquals("My Animation", c.layout.value!!.title)
                c.setLayoutTitle("   ")
                assertEquals(null, c.layout.value!!.title, "blank clears the title")
            }
        } finally { c.close() }
    }

    @Test
    fun `the name label and the value annotation hide and move independently`() {
        val c = controller()
        try {
            val q = c.inventory.namesOf(ElementKind.QUEUE).first()
            val lbl = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.QUEUE, q)
                // Hide the name label but keep the value, and offset the value separately.
                panel.setElementLabelForTest(ElementKind.QUEUE, q, null, 0.0, -12.0, visible = false,
                    valueDx = 6.0, valueDy = 22.0, valueVisible = true)
                c.layout.value!!.labelFor(ElementKind.QUEUE, q)
            }
            assertFalse(lbl!!.visible, "the name label is hidden")
            assertTrue(lbl.valueVisible, "the value/state annotation stays shown")
            assertEquals(6.0, lbl.valueDx); assertEquals(22.0, lbl.valueDy, "the value annotation is positioned on its own")
        } finally { c.close() }
    }

    @Test
    fun `storages can be placed, styled, moved on the canvas, and removed`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                panel.addStorageForTest("Oven", 100.0, 100.0, 180.0, 60.0, ksl.animation.StorageStyle.PROGRESS_BELT)
                val placed = c.layout.value!!.storages.first { it.suspensionName == "Oven" }
                val listed = panel.storageTabListForTest()
                c.setStorageStyle("Oven", ksl.animation.StorageStyle.PILE)
                panel.dragStorageToForTest("Oven", 300.0, 220.0)
                val styled = c.layout.value!!.storages.first { it.suspensionName == "Oven" }
                c.removeStorage("Oven")
                Triple(placed to listed, styled, c.layout.value!!.storages.none { it.suspensionName == "Oven" })
            }
            assertEquals(180.0, r.first.first.width, "width comes from the dragged rectangle")
            assertEquals(60.0, r.first.first.height)
            assertTrue(r.first.second.any { it.startsWith("Oven") }, "the storage is listed in the Storages tab: ${r.first.second}")
            assertEquals(ksl.animation.StorageStyle.PILE, r.second.style, "style edit applied")
            assertEquals(300.0, r.second.position.x, "canvas drag moved it")
            assertTrue(r.third, "removed")
        } finally { c.close() }
    }

    @Test
    fun `a selected storage can be resized by dragging its corner grip (item 2)`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                panel.addStorageForTest("Oven", 100.0, 100.0, 180.0, 60.0, ksl.animation.StorageStyle.PROGRESS_BELT)
                panel.selectStorageForTest("Oven")
                // 0° storage: corner grip at (x+width, y+height); drag to (250,140) → width 150, height 40.
                panel.resizeStorageForTest("Oven", 250.0, 140.0)
                c.layout.value!!.storages.first { it.suspensionName == "Oven" }
            }
            assertEquals(150.0, r.width, "width follows the dragged corner")
            assertEquals(40.0, r.height, "height follows the dragged corner")
        } finally { c.close() }
    }

    @Test
    fun `a storage can be picked anywhere on its body, selected, edited, and deleted (G6)`() {
        val c = controller()
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                // 180-wide belt at (100,100), 0° → spans x in [100,280]. The body midpoint is far from the anchor.
                panel.addStorageForTest("Oven", 100.0, 100.0, 180.0, 60.0, ksl.animation.StorageStyle.PROGRESS_BELT)
                val bodyHit = panel.storageAtWorldForTest(200.0, 100.0)   // mid-belt — old anchor-radius test missed this
                val miss = panel.storageAtWorldForTest(600.0, 600.0)
                val selShown = panel.selectStorageForTest("Oven")
                val clearShown = panel.selectStorageForTest(null)
                // Full property edit via the editor's controller path.
                panel.selectStorageForTest("Oven")
                c.setStorageProperties("Oven", 120.0, 130.0, ksl.animation.StorageStyle.LINE, 90.0, 40.0, 45.0, 8.0, 12, 5, false, "Bakery")
                val edited = c.layout.value!!.storages.first { it.suspensionName == "Oven" }
                panel.removeSelectedForTest() // Delete key removes the selected storage
                Triple(bodyHit to miss, selShown to clearShown, edited to c.layout.value!!.storages.none { it.suspensionName == "Oven" })
            }
            assertEquals("Oven", r.first.first, "clicking the belt body (not just the anchor) selects the storage")
            assertEquals(null, r.first.second, "a far click hits nothing")
            assertTrue(r.second.first, "selecting a storage shows its highlight outline")
            assertTrue(!r.second.second, "deselecting clears the outline")
            with(r.third.first) {
                assertEquals(ksl.animation.StorageStyle.LINE, style); assertEquals(120.0, position.x); assertEquals(90.0, width)
                assertEquals(45.0, growthDegrees); assertEquals(12, maxShown); assertEquals(false, byType); assertEquals("Bakery", label)
            }
            assertTrue(r.third.second, "Delete removed the selected storage")
        } finally { c.close() }
    }

    @Test
    fun `cascaded placement keeps every element inside the canvas`() {
        val c = controller()
        try {
            val onCanvas = onEdt {
                LayoutPanel(c)
                c.newBlankLayout()
                val layout0 = c.layout.value!!
                // Place a lot of elements across kinds; the old unbounded cascade ran off the bottom edge.
                val placed = buildList {
                    listOf(ElementKind.RESOURCE, ElementKind.QUEUE, ElementKind.RESPONSE, ElementKind.COUNTER).forEach { k ->
                        c.inventory.namesOf(k).forEach { c.addLayoutElement(k, it); add(k to it) }
                    }
                }
                val l = c.layout.value!!
                placed.all { (k, n) -> l.positionOf(k, n)!!.let { it.x in 0.0..l.width && it.y in 0.0..l.height } } to (placed.size to layout0.height)
            }
            assertTrue(onCanvas.first, "every cascaded element stays within the canvas (placed ${onCanvas.second.first})")
        } finally { c.close() }
    }

    @Test
    fun `layouts save and load as TOML by default, JSON still supported`() {
        val c = controller()
        try {
            val dir = java.nio.file.Files.createTempDirectory(tempRoot, "lay")
            val res = c.inventory.namesOf(ElementKind.RESOURCE).first()
            val r = onEdt {
                c.newBlankLayout(); c.addLayoutElement(ElementKind.RESOURCE, res)
                val toml = dir.resolve("d.lay.toml"); c.saveLayout(toml)
                c.newBlankLayout(); c.loadLayout(toml)           // round-trips via the TOML codec
                val loaded = c.layout.value!!.resources.any { it.resourceName == res }
                val json = dir.resolve("d.lay.json"); c.saveLayout(json) // explicit .json still writes JSON
                Triple(java.nio.file.Files.readString(toml), loaded, java.nio.file.Files.readString(json).trimStart())
            }
            assertFalse(r.first.trimStart().startsWith("{"), "TOML output is not JSON")
            assertTrue(r.second, "the TOML layout loaded back")
            assertTrue(r.third.startsWith("{"), "an explicit .json path still writes JSON")
            dir.toFile().deleteRecursively()
        } finally { c.close() }
    }

    @Test
    fun `validation strip flags an unmatched binding`() {
        val c = controller()
        try {
            val texts = onEdt {
                val panel = LayoutPanel(c)
                panel.addForTest(ElementKind.RESOURCE, "Test1")
                val good = panel.validationTextForTest()
                c.addLayoutElement(ElementKind.RESOURCE, "Ghost") // not in inventory
                panel.addForTest(ElementKind.RESOURCE, "Test1")   // any wired edit recomputes the strip
                good to panel.validationTextForTest()
            }
            assertTrue(texts.first.startsWith("✓"), "valid layout shows the check")
            assertContains(texts.second, "Ghost")
        } finally { c.close() }
    }
}
