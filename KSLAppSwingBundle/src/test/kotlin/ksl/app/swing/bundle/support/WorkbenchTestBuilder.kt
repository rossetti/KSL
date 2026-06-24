package ksl.app.swing.bundle.support

import ksl.modeling.variable.Response
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * A bare [ModelBuilderIfc] (named class, public zero-arg constructor) used as the
 * contents of a builders-JAR fixture for the Bundle Workbench tests.
 */
class WorkbenchTestBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("wb-model", autoCSVReports = false)
        Response(model, name = "throughput")
        model.numberOfReplications = 2
        model.lengthOfReplication = 50.0
        return model
    }
}
