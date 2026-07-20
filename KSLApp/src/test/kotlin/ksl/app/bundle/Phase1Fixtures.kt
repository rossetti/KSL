package ksl.app.bundle

import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.random.rvariable.ExponentialRV

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

/**
 * A builder whose model's only tunable factors come from the RANDOM VARIABLE path — an
 * M/M/1-style model with two exponential random variables and NO `@KSLControl` controls.
 * Its two numeric factors are therefore the two RV means, so its extracted descriptor has
 * `inputNames.size == 2` entirely from `rvParameterData`. Used to prove that an RV-only
 * model still qualifies for EXPERIMENT by default (the controls path is not the only one).
 */
class Phase1RvFactorBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("p1-rv-model", autoCSVReports = false)
        RandomVariable(model, ExponentialRV(6.0, 1), name = "arrivalRV")
        RandomVariable(model, ExponentialRV(3.0, 2), name = "serviceRV")
        Response(model, name = "wq")
        model.numberOfReplications = 2
        model.lengthOfReplication = 100.0
        return model
    }
}
