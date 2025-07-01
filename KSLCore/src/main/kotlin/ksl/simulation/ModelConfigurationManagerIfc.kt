package ksl.simulation

/**
 *  This interface defines a function that can be called when the model is being set up, prior to the
 *  execution of any replications. The intention is to allow the modeler to specify via the
 *  configuration map specifications for configuring the model for the experiments.  A KSL model
 *  has two methods for providing input variations, [ksl.controls.Controls] and [ksl.utilities.random.rvariable.parameters.RVParameterSetter].
 *  This interface provides a third more general method. The map can hold information that the
 *  specific implementor of the interface can use to adjust, change, update, or configure the model in the
 *  way necessary to prepare it before executing the simulation experiments. The general idea
 *  is that the key indicates what will be done or changed and the associated value can be used
 *  to make the change. For example, a key could be a model element name and the value could be a JSON string
 *  that can be deserialized to required inputs for the change.
 */
fun interface ModelConfigurationManagerIfc {

    fun configure(model: Model, configuration: Map<String, String>)

}