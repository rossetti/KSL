package ksl.app.swing.animation.app

import ksl.modeling.agent.AgentModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G3: before a run the object-style list offers process-entity types (always drawn by the process-view machinery)
 * but defers agent types (whose visual-ness is a runtime property) — so control-only agents never appear as styleable.
 * Post-run agent types are added from the trace (movers only); that path is covered by the trace-accumulator tests.
 */
class AnimationAppObjectStyleG3Test {

    private class MixedModel(parent: ModelElement) : AgentModel(parent, "mixed") {
        @Suppress("unused") inner class Drone : Agent("d")   // AgentModel.Agent subclass -> deferred pre-run
        @Suppress("unused") inner class Widget : Entity()    // plain process entity     -> surfaced pre-run
    }

    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
            Model("Mixed").apply { MixedModel(this) }
    }

    @Test
    fun `pre-run object styles keep process entities and defer agent types`() {
        val c = AnimationAppController("Mixed", builder)
        try {
            val names = c.objectStyleTypeNames()
            assertTrue("Widget" in names, "process entity should surface pre-run: $names")
            assertFalse("Drone" in names, "agent type should be deferred until a run reveals movers: $names")
        } finally {
            c.close()
        }
    }
}
