package ksl.utilities.statistic

import ksl.utilities.GetValueIfc

interface WeightedCollectorIfc : CollectorIfc {

    /**
     * The last weight collected
     */
    val weight: Double

    /**
     * Collect on the supplied value
     *
     * @param obs a double representing the observation
     */
    override fun collect(obs: Double) {
        collect(obs, 1.0)
    }

    /**
     * Collect on the supplied value
     *
     * @param obs a double representing the observation
     * @param weight a double representing the weight of the observation
     */
    fun collect(obs: Double, weight: Double)

    /**
     * Collects on the values in the supplied array.
     *
     * @param observations the values, must not be null
     * @param weights the weights of the observations
     */
    fun collect(observations: DoubleArray, weights: DoubleArray) {
        require(observations.size == weights.size) { "The size of the arrays must be equal!" }
        for(i in observations.indices){
            collect(observations[i], weights[i])
        }
    }

    /**
     * Collects on the values returned by the supplied GetValueIfc
     *
     * @param v the object that returns the value to be collected
     * @param w the weight associated with the object
     */
    fun collect(v: GetValueIfc, w: GetValueIfc) {
        collect(v.value(), w.value())
    }
}