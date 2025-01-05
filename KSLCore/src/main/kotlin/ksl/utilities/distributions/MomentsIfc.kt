package ksl.utilities.distributions

interface MomentsIfc {

    val mean: Double
    val variance: Double
    val skewness: Double
    val kurtosis: Double

}