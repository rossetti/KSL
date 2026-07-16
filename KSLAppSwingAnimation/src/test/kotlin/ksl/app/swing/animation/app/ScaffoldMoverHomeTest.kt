package ksl.app.swing.animation.app

import ksl.examples.general.animationbundle.Example13MovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Group A: the scaffold must anchor movable resources at a station so they render on the static Layout tab
 * (Example 13's movers were placed but had no position, so they only appeared during replay).
 */
class ScaffoldMoverHomeTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model = Example13MovableResources.buildModel()
    }

    @Test
    fun `scaffold movable resources have a static position`() {
        val c = AnimationAppController("MR", builder)
        try {
            val movers = c.buildScaffoldLayout()?.movableResources ?: emptyList()
            assertTrue(movers.isNotEmpty(), "movable resources are placed by the scaffold")
            assertTrue(movers.all { it.position != null }, "each mover has a static position: ${movers.map { it.name to it.position }}")
        } finally {
            c.close()
        }
    }
}
