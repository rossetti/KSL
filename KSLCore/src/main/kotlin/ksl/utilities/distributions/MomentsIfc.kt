package ksl.utilities.distributions

import kotlin.math.sqrt

/**
 * An interface defining the moments of a statistical distribution.
 * It explicitly separates Raw Moments (moments about the origin, zero)
 * from Central Moments (moments about the mean).
 */
interface MomentsIfc {

    // --- Core Descriptive Properties (Abstract) ---
    val mean: Double
    val variance: Double
    val skewness: Double
    val kurtosis: Double

    // --- Central Moments (Moments about the mean) ---

    /** The 2nd central moment is exactly the variance. */
    val secondCentralMoment: Double
        get() = variance

    /** The 3rd central moment, derived from skewness. */
    val thirdCentralMoment: Double
        get() {
            val v = variance
            return skewness * v * sqrt(v)
        }

    /** The 4th central moment, derived from kurtosis. */
    val fourthCentralMoment: Double
        get() {
            val v = variance
            return kurtosis * v * v
        }

    // --- Raw Moments (Moments about the origin) ---

    /** The 1st raw moment is exactly the mean. */
    val firstRawMoment: Double
        get() = mean

    /** The 2nd raw moment: E(X^2) = Var(X) + E(X)^2 */
    val secondRawMoment: Double
        get() {
            val m = mean
            return variance + (m * m)
        }

    /** The 3rd raw moment: E(X^3) = E((X-m)^3) + 3m*Var(X) + m^3, where m is the mean */
    val thirdRawMoment: Double
        get() {
            val m = mean
            val m2 = m * m
            return thirdCentralMoment + (3 * m * variance) + (m2 * m)
        }

    /** The 4th raw moment: E(X^4) */
    val fourthRawMoment: Double
        get() {
            val m = mean
            val m2 = m * m
            val m3 = m2 * m
            val m4 = m2 * m2
            return fourthCentralMoment + (4 * m * thirdCentralMoment) + (6 * m2 * variance) + m4
        }
}