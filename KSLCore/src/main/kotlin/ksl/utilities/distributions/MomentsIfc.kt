package ksl.utilities.distributions

import kotlin.math.sqrt

interface MomentsIfc {

    val mean: Double
    val variance: Double
    val skewness: Double
    val kurtosis: Double

    val secondMoment: Double
        get() {
            val m = mean
            return variance + m * m
        }

    val thirdMoment: Double
        get() {
            val v = variance
            return skewness * v * sqrt(v)
        }

    val fourthMoment: Double
        get() {
            val v = variance
            return kurtosis * v * v
        }

}