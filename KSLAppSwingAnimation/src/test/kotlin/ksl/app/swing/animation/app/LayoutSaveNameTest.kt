package ksl.app.swing.animation.app

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The suggested *Save Layout As* file name must prefix with the model (like the produced trace file), not the
 * auto-layout's "Replay" title. Mirrors the trace-naming base.
 */
class LayoutSaveNameTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model = Model("Drive Through Pharmacy")
    }

    @Test
    fun `suggested layout base name is the sanitized model name`() {
        val c = AnimationAppController("Anim", builder)
        try {
            assertEquals("Drive_Through_Pharmacy", c.suggestedLayoutBaseName())
        } finally {
            c.close()
        }
    }
}
