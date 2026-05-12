package ksl.bundle.tools.support

import ksl.app.bundle.KSLAppKind
import ksl.app.bundle.KSLBundledModel
import ksl.app.bundle.KSLModelBundle
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * Minimal `KSLModelBundle` implementation used by `TestBundleBuilder` to
 * synthesize a real bundle JAR for tests. Public no-arg constructor — the
 * `ServiceLoader` requirement — and one model that builds a trivial
 * `ksl.simulation.Model` with no children.
 */
class StubBundle : KSLModelBundle {

    override val bundleId: String = "test.stub"
    override val displayName: String = "Stub Bundle"
    override val description: String = "A test bundle with one trivial model."
    override val version: String = "0.0.1"
    override val kslApiVersion: String = "1.2"

    override val models: List<KSLBundledModel> = listOf(StubModel)

    private object StubModel : KSLBundledModel {
        override val modelId: String = "stub"
        override val displayName: String = "Stub Model"
        override val description: String = "Trivial model used only by tests."
        override val supportedApps: Set<KSLAppKind> = setOf(KSLAppKind.SINGLE)

        override fun builder(): ModelBuilderIfc = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model = Model(modelId, autoCSVReports = false)
        }
    }
}
