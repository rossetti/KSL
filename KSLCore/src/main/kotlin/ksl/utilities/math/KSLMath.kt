/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ksl.utilities.math

import ksl.utilities.distributions.Gamma
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.*

/**
 * This class implements additional mathematical functions and determines the
 * parameters of the floating point representation.
 *
 *
 * This is based on the DhbMath class of Didier Besset in "Object-Oriented
 * Implementation of Numerical Methods", Morgan-Kaufmann
 */
object KSLMath {

    /**
     *  Use for general logging
     */
    val logger = KotlinLogging.logger {}

    /**
     * holds initial factorials
     */
    private val a = DoubleArray(33)
    private var ntop = 4

    init {
        a[0] = 1.0
        a[1] = 1.0
        a[2] = 2.0
        a[3] = 6.0
        a[4] = 24.0
    }

    private const val maxLnTop = 101
    private val lna = DoubleArray(maxLnTop)

    /**
     * Values used to compute human readable scales.
     */
    private val scales = doubleArrayOf(1.25, 2.0, 2.5, 4.0, 5.0, 7.5, 8.0, 10.0)
    private val semiIntegerScales = doubleArrayOf(2.0, 2.5, 4.0, 5.0, 7.5, 8.0, 10.0)
    private val integerScales = doubleArrayOf(2.0, 4.0, 5.0, 8.0, 10.0)

    /**
     * A variable that can be used in algorithms to specify the maximum number
     * of iterations. This is an object property and thus a change will change it for any
     * algorithm that depends on this variable. Must be greater than 0
     *
     *
     */
    var maxNumIterations = 200
        set(iterations) {
            require(iterations > 0) { "The number of iterations must be > 0, recommended at least 100." }
            field = iterations
        }

    /**
     * Radix used by floating-point numbers.
     */
    val radix = computeRadix()

    /**
     * Largest positive value which, when added to 1.0, yields 0.
     */
    val machinePrecision = computeMachinePrecision()

    /**
     * The default numerical precision. This represents an estimate of the
     * precision expected for a general numerical computation. For example, two
     * numbers x and y can be considered equal if the relative difference
     * between them is less than the default numerical precision. This value has
     * been defined as the square root of the machine precision
     */
    val defaultNumericalPrecision = sqrt(machinePrecision)

    /**
     * Largest positive value which, when subtracted to 1.0, yields 0.
     */
    val negativeMachinePrecision = computeNegativeMachinePrecision()

    /**
     * Smallest number different from zero.
     */
    val smallestNumber = computeSmallestNumber()

    /**
     * Largest possible number
     */
    val largestNumber = computeLargestNumber()

    /**
     * Typical meaningful small number for numerical calculations.
     * A number that can be added to some value without noticeably
     * changing the result of the computation
     */
    val smallNumber = sqrt(smallestNumber)

    /**
     * Largest argument for an exponential
     */
    val largestExponentialArgument = ln(largestNumber)

    /**
     * Smallest argument for the exponential
     */
    val smallestExponentialArgument = ln(smallestNumber)

    private fun computeRadix(): Int {
        var a = 1.0
        var tmp1: Double
        var tmp2: Double
        do {
            a += a
            tmp1 = a + 1.0
            tmp2 = tmp1 - a
        } while (tmp2 - 1.0 != 0.0)
        var b = 1.0
        var rx = 0
        while (rx == 0) {
            b += b
            tmp1 = a + b
            rx = (tmp1 - a).toInt()
        }
        return rx
    }

    private fun computeMachinePrecision(): Double {
        val floatingRadix = radix.toDouble()
        val inverseRadix = 1.0 / floatingRadix
        var mp = 1.0
        var tmp = 1.0 + mp
        while ((tmp - 1.0) != 0.0) {
            mp *= inverseRadix
            tmp = 1.0 + mp
        }
        return mp
    }

    private fun computeNegativeMachinePrecision(): Double {
        val floatingRadix = radix.toDouble()
        val inverseRadix = 1.0 / floatingRadix
        var nmp = 1.0
        var tmp = 1.0 - nmp
        while (tmp - 1.0 != 0.0) {
            nmp *= inverseRadix
            tmp = 1.0 - nmp
        }
        return nmp
    }

    private fun computeLargestNumber(): Double {
        val floatingRadix = radix.toDouble()
        var fullMantissaNumber = (1.0
                - floatingRadix * negativeMachinePrecision)
        var lgn = 0.0
        while (!java.lang.Double.isInfinite(fullMantissaNumber)) {
            lgn = fullMantissaNumber
            fullMantissaNumber *= floatingRadix
        }
        return lgn
    }

    private fun computeSmallestNumber(): Double {
        val floatingRadix = radix.toDouble()
        val inverseRadix = 1.0 / floatingRadix
        var fullMantissaNumber = 1.0 - floatingRadix * negativeMachinePrecision
        var sn = 0.0
        while (fullMantissaNumber != 0.0) {
            sn = fullMantissaNumber
            fullMantissaNumber *= inverseRadix
        }
        return sn
    }

    /**
     * Compares two numbers a and b and checks if they are within the supplied
     * precision of each other.
     *
     * @param a         double the first number
     * @param b         double the second number
     * @param precision double the precision for checking, default is defaultNumericalPrecision()
     * @return boolean    true if the relative difference between a and b is less
     * than precision
     */
    fun equal(a: Double, b: Double, precision: Double = defaultNumericalPrecision): Boolean {
        val norm = max(abs(a), abs(b))
        return norm < precision || abs(a - b) < precision * norm
    }

    /**
     * Returns true if Math.abs(a-b) &lt; precision
     *
     * @param a         the first number
     * @param b         the second number
     * @param precision the precision to check
     * @return true if within the precision
     */
    fun within(a: Double, b: Double, precision: Double): Boolean {
        return abs(a - b) < precision
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Floating-point machine parameters")
        sb.appendLine("---------------------------------")
        sb.appendLine("radix = $radix")
        sb.appendLine("Machine precision = $machinePrecision")
        sb.appendLine("Default precision = $defaultNumericalPrecision")
        sb.appendLine("Negative machine precision = $negativeMachinePrecision")
        sb.appendLine("Smallest positive number = $smallestNumber")
        sb.appendLine("Largest positive number = $largestNumber")
        sb.appendLine("Small number = $smallNumber")
        sb.appendLine("Largest exponential argument = $largestExponentialArgument")
        sb.appendLine("Smallest exponential argument = $smallestExponentialArgument")
        sb.appendLine("1.0 - getMachinePrecision() = ${(1.0 - machinePrecision)}")
        sb.appendLine("0.0 + getMachinePrecision() = ${(0.0 + machinePrecision)}")
        sb.appendLine("1.0 - getDefaultNumericalPrecision() = ${(1.0 - defaultNumericalPrecision)}")
        sb.append("0.0 + getDefaultNumericalPrecision() = ${(0.0 + defaultNumericalPrecision)}")
//        sb.append(System.lineSeparator())
        return sb.toString()
    }

    /**
     * This method returns the specified value rounded to the nearest integer
     * multiple of the specified scale.
     *
     * @param value number to be rounded
     * @param scale defining the rounding scale
     * @return rounded value
     */
    fun roundTo(value: Double, scale: Double): Double {
        return (value / scale).roundToInt() * scale
    }

    /**
     * Round the specified value upward to the next scale value.
     *
     * @param value         the value to be rounded.
     * @param integerValued flag specified whether integer scale are used,
     * otherwise double scale is used.
     * @return a number rounded upward to the next scale value.
     */
    fun roundToScale(value: Double, integerValued: Boolean): Double {
        val scaleValues: DoubleArray
        var orderOfMagnitude = floor(ln(value) / ln(10.0)).toInt()
        if (integerValued) {
            orderOfMagnitude = 1.coerceAtLeast(orderOfMagnitude)
//            orderOfMagnitude = Math.max(1, orderOfMagnitude)
            scaleValues = if (orderOfMagnitude == 1) {
                integerScales
            } else if (orderOfMagnitude == 2) {
                semiIntegerScales
            } else {
                scales
            }
        } else {
            scaleValues = scales
        }
        val exponent = 10.0.pow(orderOfMagnitude.toDouble())
        val rValue = value / exponent
        for (n in scaleValues.indices) {
            if (rValue <= scaleValues[n]) {
                return scaleValues[n] * exponent
            }
        }
        return exponent // Should never reach here
    }

    /**
     * Get the sign of the number based on the equal() method Equal is 0.0,
     * positive is 1.0, negative is -1.0
     *
     * @param x the number
     * @return the sign of the number
     */
    fun sign(x: Double): Double {
        if (equal(0.0, x)) {
            return 0.0
        }
        return if (x > 0.0) {
            1.0
        } else {
            -1.0
        }
    }

    /**
     * Returns the factorial (n!) of the number
     *
     * @param n The number to take the factorial of
     * @return The factorial of the number.
     */
    fun factorial(n: Int): Double {
        require(n >= 0) { "Argument must be > 0" }
        if (n > 32) {
            return exp(Gamma.logGammaFunction(n + 1.0))
        }
        var j: Int
        while (ntop < n) {
            j = ntop++
            a[ntop] = a[j] * ntop
        }
        return a[n]
    }

    /**
     * Computes the binomial coefficient. Computes the number of combinations of
     * size k that can be formed from n distinct objects.
     *
     * @param n The total number of distinct items
     * @param k The number of subsets
     * @return the binomial coefficient
     */
    fun binomialCoefficient(n: Int, k: Int): Double {
        return floor(0.5 + exp(logFactorial(n) - logFactorial(k) - logFactorial(n - k)))
    }

    /**
     * Computes the natural logarithm of the factorial operator. ln(n!)
     *
     * @param n The value to be operated on.
     * @return the log of the factorial
     */
    fun logFactorial(n: Int): Double {
        require(n >= 0) { "Argument must be > 0" }
        if (n <= 1) {
            return 0.0
        }
        return if (n < maxLnTop) {
            if (lna[n] > 0) // already been computed
            {
                lna[n] // just return it
            } else {
                lna[n] =
                    Gamma.logGammaFunction(n + 1.0) // compute it, save it, return it
                lna[n]
            }
        } else {
            Gamma.logGammaFunction(n + 1.0)
        }
    }

    /** Computes the binomial coefficient.  Computes the number of combinations of size k
     * that can be formed from n distinct objects.
     * @param n The total number of distinct items
     * @param k The number of subsets
     * @return the coefficient
     */
    fun binomialCoefficient(n: Double, k: Double): Double {
        return exp(logFactorial(n) - logFactorial(k) - logFactorial(n - k))
    }

    /** Computes the natural logarithm of the factorial operator.
     * ln(n!)
     * @param n The value to be operated on.
     * @return the natural log of the factorial
     */
    fun logFactorial(n: Double): Double {
        return Gamma.logGammaFunction(n + 1.0)
    }

    /**
     * Converts a double to a byte. If the double is outside
     * the natural range, then the value is set to the minimum or
     * maximum of the range. If within the range, the value
     * is rounded to the nearest value. For example, 4.9999 is
     * rounded to 5.0.
     *
     * @param value the value to convert
     * @return the converted value
     */
    fun toByteValue(value: Double): Byte {
        return if (value >= Byte.MAX_VALUE) {
            logger.trace { "$value was limited to ${Byte.MAX_VALUE} in toByteValue()" }
            Byte.MAX_VALUE
        } else if (value <= Byte.MIN_VALUE) {
            logger.trace { "$value was limited to ${Byte.MIN_VALUE} in toByteValue()" }
            Byte.MIN_VALUE
        } else {
            // in the range of byte, convert to the nearest byte
            value.roundToInt().toByte()
        }
    }

    /**
     * Converts a double to a long. If the double is outside
     * the natural range, then the value is set to the minimum or
     * maximum of the range. If within the range, the value
     * is rounded to the nearest value. For example, 4.9999 is
     * rounded to 5.0.
     *
     * @param value the value to convert
     * @return the converted value
     */
    fun toLongValue(value: Double): Long {
        return if (value >= Long.MAX_VALUE) {
            logger.trace { "$value was limited to ${Long.MAX_VALUE} in toLongValue()" }
            Long.MAX_VALUE
        } else if (value <= Long.MIN_VALUE) {
            logger.trace { "$value was limited to ${Long.MIN_VALUE} in toLongValue()" }
            Long.MIN_VALUE
        } else {
            // in the range of long, convert to the nearest long
            value.roundToLong()
        }
    }

    /**
     * Converts a double to an int. If the double is outside
     * the natural range, then the value is set to the minimum or
     * maximum of the range. If within the range, the value
     * is rounded to the nearest value. For example, 4.9999 is
     * rounded to 5.0.
     *
     * @param value the value to convert
     * @return the converted value
     */
    fun toIntValue(value: Double): Int {
        return if (value >= Int.MAX_VALUE) {
            logger.trace { "$value was limited to ${Int.MAX_VALUE} in toIntValue()" }
            Int.MAX_VALUE
        } else if (value <= Int.MIN_VALUE) {
            logger.trace { "$value was limited to ${Int.MIN_VALUE} in toIntValue()" }
            Int.MIN_VALUE
        } else {
            // in the range of int, convert to the nearest int
            value.roundToInt()
        }
    }

    /**
     * Converts a double to a short. If the double is outside
     * the natural range, then the value is set to the minimum or
     * maximum of the range. If within the range, the value
     * is rounded to the nearest value. For example, 4.9999 is
     * rounded to 5.0.
     *
     * @param value the value to convert
     * @return the converted value
     */
    fun toShortValue(value: Double): Short {
        return if (value >= Short.MAX_VALUE) {
            logger.trace { "$value was limited to ${Short.MAX_VALUE} in toShortValue()" }
            Short.MAX_VALUE
        } else if (value <= Short.MIN_VALUE) {
            logger.trace { "$value was limited to ${Short.MIN_VALUE} in toShortValue()" }
            Short.MIN_VALUE
        } else {
            // in the range of int, convert to the nearest short
            value.roundToInt().toShort()
        }
    }

    /**
     * Converts a double to a boolean. 1.0 is true. Double.NEGATIVE_INFINITY is mapped to false,
     * Double.POSITIVE_INFINITY is mapped to true, any other double values other are mapped to false.
     *
     * @param value the value to convert
     * @return the converted value
     */
    fun toBooleanValue(value: Double): Boolean {
        return if (value == 1.0) {
            logger.trace { "$value was converted to true in toBooleanValue()" }
            true
        } else {
            if (value == Double.NEGATIVE_INFINITY) {
                logger.trace { "$value was converted to false in toBooleanValue()" }
                return false
            } else if (value == Double.POSITIVE_INFINITY) {
                logger.trace { "$value was converted to true in toBooleanValue()" }
                return true
            } else if (value != 0.0) {
                logger.trace { "$value was converted to false in toBooleanValue()" }
                return false
            }
            false
        }
    }

    /**
     * Converts a double to a float. Standard loss of precision
     * as noted by the Java Language Specification will occur
     * as per Double.floatValue()
     *
     * @param value the value to convert
     * @return the converted value
     */
    fun toFloatValue(value: Double): Float {
        // standard loss of precision is expected
        return value.toFloat()
    }
}
