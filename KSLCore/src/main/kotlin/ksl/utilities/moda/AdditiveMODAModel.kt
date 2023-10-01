package ksl.utilities.moda

/**
 *  Represents a multi-objective decision analysis (MODA) model that uses
 *  an additive model for the attribute valuation.
 *
 */
class AdditiveMODAModel() : MODAModel() {

    val weights: Map<MetricIfc, Double>
        get() {
            TODO()
        }

    override fun multiObjectiveValue(alternative: String): Double {
        TODO("Not yet implemented")
    }

}