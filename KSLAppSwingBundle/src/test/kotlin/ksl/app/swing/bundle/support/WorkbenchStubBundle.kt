package ksl.app.swing.bundle.support

import ksl.app.bundle.KSLAppKind
import ksl.app.bundle.KSLBundledModel
import ksl.app.bundle.KSLModelBundle
import ksl.modeling.variable.Counter
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * Test bundle for the Workbench controller: one model with two counters, the
 * first nominated via `curateCatalog`. The second is an un-nominated candidate
 * the controller test can nominate and write back.
 */
class WorkbenchStubBundle : KSLModelBundle {
    override val bundleId: String = "test.workbench"
    override val displayName: String = "Workbench Stub Bundle"
    override val description: String = "Two counters; one pre-nominated."
    override val version: String? = "0.0.1"
    override val kslApiVersion: String? = "1.2"
    override val models: List<KSLBundledModel> = listOf(M)

    private object M : KSLBundledModel {
        override val modelId: String = "wbstub"
        override val displayName: String = "Workbench Stub Model"
        override val description: String = "Two counters."
        override val supportedApps: Set<KSLAppKind> = setOf(KSLAppKind.SINGLE)
        override fun builder(): ModelBuilderIfc = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model = Model(modelId, autoCSVReports = false).apply {
                val a = Counter(this, name = "a")
                Counter(this, name = "b")
            }
        }
    }
}
