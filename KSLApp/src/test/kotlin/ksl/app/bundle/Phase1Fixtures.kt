package ksl.app.bundle

import ksl.modeling.variable.Response
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * A minimal, top-level [ModelBuilderIfc] used as a test fixture for the
 * manifest-driven (data-driven) bundle path. It is named, public, and has a
 * public zero-argument constructor — the contract a builders JAR must satisfy.
 *
 * Its built model carries one response so the extracted `ModelDescriptor` has
 * observable content.
 */
class Phase1TestBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("p1-model", autoCSVReports = false)
        Response(model, name = "throughput")
        model.numberOfReplications = 2
        model.lengthOfReplication = 100.0
        return model
    }
}

/** A second builder, used to confirm a Kotlin `object` builder is also supported. */
object Phase1ObjectBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("p1-object-model", autoCSVReports = false)
        Response(model, name = "utilization")
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        return model
    }
}
