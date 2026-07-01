package ksl.app.swing.animation.app

import ksl.animation.AnimationLayout
import ksl.animation.LayoutPoint
import ksl.animation.LocationLayoutElement
import ksl.modeling.agent.AgentLike
import ksl.modeling.agent.AgentModel
import ksl.modeling.entity.ProcessModel
import ksl.modeling.spatial.DistancesModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * G1: model-declared named locations (an agent `Context.location(...)`) are stamped onto the auto-layout as
 * placed `LocationLayoutElement`s at their coordinates — surfaced with no hand-authored layout.
 */
class AnimationAppLocationTest {

    private class TinyAgents(parent: ModelElement) : AgentModel(parent, "tiny") {
        val sky = Context<AgentLike>("space")
        init { sky.location("Depot", 10.0, 20.0) }
    }

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("Loc").apply { TinyAgents(this) }
    }

    @Test
    fun `named locations are stamped onto the scaffold layout at their coordinates`() {
        val c = AnimationAppController("Loc", builder)
        try {
            val depot = c.buildScaffoldLayout()?.locations?.firstOrNull { it.locationName == "Depot" }
            assertTrue(
                depot != null && depot.position == LayoutPoint(10.0, 20.0),
                "Depot should be stamped at its coordinates from the inventory, got $depot"
            )
        } finally {
            c.close()
        }
    }

    // Phase 5: a coordinate-free DistancesModel — its locations are MDS-placed in the inventory.
    private class DistModel(parent: ModelElement) : ProcessModel(parent, "dist") {
        init {
            val dm = DistancesModel()
            val a = dm.Location("A"); val b = dm.Location("B"); val c = dm.Location("C")
            dm.addDistance(a, b, 10.0, symmetric = true)
            dm.addDistance(b, c, 10.0, symmetric = true)
            dm.addDistance(a, c, 10.0, symmetric = true)
            spatialModel = dm
        }
    }

    private val distBuilder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("Dist").apply { DistModel(this) }
    }

    @Test
    fun `withModelLocations overrides a location position with the model MDS position`() {
        val c = AnimationAppController("Dist", distBuilder)
        try {
            // An arbitrary placeholder (as auto-layout's ring would produce) is overridden by the inventory's MDS.
            val layout = AnimationLayout(locations = listOf(LocationLayoutElement("A", LayoutPoint(-999.0, -999.0))))
            val a = c.withModelLocations(layout).locations.first { it.locationName == "A" }
            assertNotNull(a.position)
            assertTrue(a.position != LayoutPoint(-999.0, -999.0), "the placeholder position is overridden by MDS, got ${a.position}")
        } finally {
            c.close()
        }
    }
}
