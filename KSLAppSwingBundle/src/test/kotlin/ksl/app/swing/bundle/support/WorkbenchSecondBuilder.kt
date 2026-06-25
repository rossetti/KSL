package ksl.app.swing.bundle.support

import ksl.modeling.variable.Response
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * A second bare [ModelBuilderIfc] so a builders-JAR fixture can hold two discovered
 * models — used to exercise the Bundle Workbench's "In bundle" include/exclude toggle.
 */
class WorkbenchSecondBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("wb-second", autoCSVReports = false)
        Response(model, name = "throughput")
        model.numberOfReplications = 2
        model.lengthOfReplication = 50.0
        return model
    }
}
