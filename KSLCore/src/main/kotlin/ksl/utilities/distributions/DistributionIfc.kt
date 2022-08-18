package ksl.utilities.distributions

import ksl.utilities.NewInstanceIfc
import ksl.utilities.random.ParametersIfc
import ksl.utilities.random.rvariable.GetRVariableIfc

/**
 * Represents the basic interface that probability distributions must implement.
 *
 */
interface DistributionIfc<T> : DistributionFunctionIfc, ParametersIfc, NewInstanceIfc<T>, GetRVariableIfc {
}