package ksl.app.swing.animation.app

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.animation.animationInventory
import ksl.animation.scaffoldLayout
import ksl.animation.validateAgainst
import ksl.app.session.RunResult
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ReplayModel
import ksl.animation.TraceFileReader
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the D + A scaffold change: a starter layout for a movable-resource model places the
 * transporters as movement glyphs (not static boxes), places the DistancesModel's locations as station
 * anchors, validates cleanly, and — in replay — the transporters resolve to on-canvas positions (they
 * actually move). Headless.
 */
class ScaffoldMovementTest {

    @TempDir
    lateinit var tempRoot: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("TRMove").apply { numberOfReplications = 1; lengthOfReplication = 200.0; TestAndRepairShopWithMovableResources(this, "TR") }
    }

    @Test
    fun `scaffold places movers as movableResource glyphs and validates`() {
        val m = Model("TRScaf").also { TestAndRepairShopWithMovableResources(it, "TR") }
        val inv = m.animationInventory()
        val scaffold = m.scaffoldLayout()

        assertTrue(inv.movableResources.isNotEmpty(), "the model has movable resources")
        // Movers appear as movableResource bindings, matching the inventory exactly…
        assertEquals(inv.movableResources.toSet(), scaffold.movableResources.map { it.name }.toSet())
        // …and NOT as static resource boxes (the old mis-classification).
        inv.movableResources.forEach { name ->
            assertFalse(scaffold.resources.any { it.resourceName == name }, "$name must not be a static resource")
        }
        // DistancesModel locations are placed as station anchors so movement can resolve.
        assertTrue(scaffold.stations.isNotEmpty(), "distance-model locations placed as stations")
        // V2: responses/counters are no longer auto-placed (declutter).
        assertTrue(scaffold.values.isEmpty(), "scaffold omits response/counter read-outs")
        // The fix restores the documented invariant (relaxed in 9F.2): a scaffold validates.
        val report = scaffold.validateAgainst(m)
        assertTrue(report.isValid, "scaffold should validate now: $report")
    }

    @Test
    fun `transporters resolve to on-canvas positions during replay`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-move")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            c.submit()
            val result = runBlocking { withTimeout(120_000) { c.lastResult.filterNotNull().first() } }
            assertIs<RunResult.Completed>(result)
            val (header, events) = TraceFileReader.readAll(c.lastTraceFile.value!!)
            val scaffold = c.buildScaffoldLayout()!!
            val model = ReplayModel.build(AnimationSource(scaffold, header, events))

            val mover = scaffold.movableResources.first().name
            val tMax = events.maxOf { it.simTime }
            var resolved = 0
            var outOfBounds = 0
            var t = 0.0
            while (t <= tMax) {
                val p = model.spatialElementPositionAt(mover, t)
                if (p != null) {
                    resolved++
                    if (p.x < 0.0 || p.x > scaffold.width || p.y < 0.0 || p.y > scaffold.height) outOfBounds++
                }
                t += tMax / 200.0
            }
            assertTrue(resolved > 0, "transporter '$mover' resolves to a position at some sampled time (it moves)")
            assertEquals(0, outOfBounds, "resolved transporter positions stay within the scaffold canvas")
        } finally { c.close(); ws.toFile().deleteRecursively() }
    }
}
