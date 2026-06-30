package ksl.app.swing.animation.app

import ksl.animation.ElementKind
import ksl.animation.animationInventory
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResource
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.modeling.entity.ProcessModel
import ksl.utilities.random.rvariable.ConstantRV
import java.nio.file.Path
import javax.swing.SwingUtilities
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P6b (C4/C5): a movable resource can be placed on the canvas with an editable position, and — when it has a
 * home base whose location is placed — its tool drops it at home.
 */
class MoverPlacementTest {

    @TempDir
    lateinit var tempRoot: Path

    /** A minimal model: a DistancesModel with Home/Depot and a Truck whose home base is Home. */
    private class HomeModel(m: Model) : ProcessModel(m, "Fleet") {
        private val dm = DistancesModel()
        val home = dm.Location("Home")
        val depot = dm.Location("Depot")
        init {
            dm.addDistance(home, depot, 50.0); dm.addDistance(depot, home, 50.0)
            dm.defaultVelocity = ConstantRV(1.0); spatialModel = dm
        }
        val truck = MovableResource(this, depot, ConstantRV(1.0), name = "Truck").apply {
            initialHomeBase = home; homeBase = home
        }
    }

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("FleetModel").also { HomeModel(it) }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `inventory maps a mover to its home-base location`() {
        val inv = builder.build(null, null).animationInventory()
        assertTrue("Truck" in inv.movableResources, "the mover is in the inventory")
        assertEquals("Home", inv.movableHomeBases["Truck"], "the mover's home base location is exposed")
    }

    @Test
    fun `placing a mover stores an editable position`() {
        val c = AnimationAppController("fleet", builder)
        try {
            val pos = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                c.placeLayoutElement(ElementKind.MOVABLE_RESOURCE, "Truck", 200.0, 150.0)
                val placed = c.layout.value!!.positionOf(ElementKind.MOVABLE_RESOURCE, "Truck")
                c.moveLayoutElement(ElementKind.MOVABLE_RESOURCE, "Truck", 220.0, 160.0)
                placed to c.layout.value!!.positionOf(ElementKind.MOVABLE_RESOURCE, "Truck")
            }
            assertEquals(200.0, pos.first?.x); assertEquals(150.0, pos.first?.y)
            assertEquals(220.0, pos.second?.x, "the mover's X/Y is editable (moved)")
        } finally { c.close() }
    }

    @Test
    fun `an at-rest mover anchors to its home station and tracks it`() {
        val c = AnimationAppController("fleet", builder)
        try {
            val r = onEdt {
                c.newBlankLayout()
                c.placeLayoutElement(ElementKind.STATION, "Home", 300.0, 90.0)
                c.placeMoverAtHome("Truck")
                val atHome = c.layout.value!!.positionOf(ElementKind.MOVABLE_RESOURCE, "Truck")
                // Moving the home station moves the mover with it (anchored, not parked at a stale point).
                c.moveLayoutElement(ElementKind.STATION, "Home", 400.0, 120.0)
                atHome to c.layout.value!!.positionOf(ElementKind.MOVABLE_RESOURCE, "Truck")
            }
            assertEquals(300.0, r.first?.x); assertEquals(90.0, r.first?.y)
            assertEquals(400.0, r.second?.x, "the mover tracks its home station after the station moves")
            assertEquals(120.0, r.second?.y)
        } finally { c.close() }
    }

    @Test
    fun `loadLayout backfills mover home base from the inventory`() {
        val c = AnimationAppController("fleet", builder)
        try {
            val dir = java.nio.file.Files.createTempDirectory(tempRoot, "fleet-load")
            val file = dir.resolve("legacy.lay.toml")
            // A "legacy" layout (saved before homeBase existed): a placed Home station and a mover with a stale
            // parked position and no homeBase. Loading should backfill the home base from the inventory.
            ksl.animation.AnimationLayout(
                stations = listOf(ksl.animation.StationLayoutElement("Home", ksl.animation.LayoutPoint(300.0, 90.0))),
                movableResources = listOf(ksl.animation.MovableResourceLayoutElement(
                    name = "Truck", position = ksl.animation.LayoutPoint(700.0, 50.0)))
            ).writeTomlToFile(file)
            val r = onEdt {
                c.loadLayout(file)
                c.layout.value!!.movableResources.first().homeBase to
                    c.layout.value!!.positionOf(ElementKind.MOVABLE_RESOURCE, "Truck")
            }
            assertEquals("Home", r.first, "home base is backfilled from the inventory on load")
            assertEquals(300.0, r.second?.x, "the loaded mover anchors to its home station, not the stale parked position")
            assertEquals(90.0, r.second?.y)
        } finally { c.close() }
    }

    @Test
    fun `placeMoverAtHome drops the mover at its placed home location`() {
        val c = AnimationAppController("fleet", builder)
        try {
            val r = onEdt {
                c.newBlankLayout()
                val before = c.placeMoverAtHome("Truck")                 // home location not placed yet
                c.placeLayoutElement(ElementKind.STATION, "Home", 300.0, 90.0) // place the home location
                val after = c.placeMoverAtHome("Truck")
                Triple(before, after, c.layout.value!!.positionOf(ElementKind.MOVABLE_RESOURCE, "Truck"))
            }
            assertTrue(!r.first, "no-op when the home location isn't placed")
            assertTrue(r.second, "places once the home location exists")
            assertEquals(300.0, r.third?.x); assertEquals(90.0, r.third?.y)
        } finally { c.close() }
    }
}
