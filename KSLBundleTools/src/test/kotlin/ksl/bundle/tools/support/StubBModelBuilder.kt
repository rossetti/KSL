package ksl.bundle.tools.support

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * A second minimal [ModelBuilderIfc] (default `modelId` "StubB") used alongside
 * [StubModelBuilder] for multi-model `kslpkg assemble` tests — e.g. exercising
 * `--exclude`.
 */
class StubBModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model = Model("StubB", autoCSVReports = false)
}
