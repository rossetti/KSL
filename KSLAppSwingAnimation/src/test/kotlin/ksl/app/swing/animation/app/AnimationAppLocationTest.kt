package ksl.app.swing.animation.app

import ksl.animation.LayoutPoint
import ksl.modeling.agent.AgentLike
import ksl.modeling.agent.AgentModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelElement
import kotlin.test.Test
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
}
