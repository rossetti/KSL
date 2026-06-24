package ksl.bundle.tools.support

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * A minimal top-level [ModelBuilderIfc] used as the input for `kslpkg assemble`
 * tests: a plain *builders-JAR* ingredient (no `KSLModelBundle` class, no
 * `META-INF/services` registration) that `ksl.app.bundle.BuilderDiscovery` can
 * find reflectively. Its default `modelId` — via
 * `BundleAuthoringSession.defaultModelId` — is "Stub" (the class name with the
 * "ModelBuilder" suffix stripped).
 */
class StubModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model = Model("Stub", autoCSVReports = false)
}
