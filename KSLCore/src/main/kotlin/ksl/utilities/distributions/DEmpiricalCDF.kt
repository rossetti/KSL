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
package ksl.utilities.distributions

import kotlinx.serialization.Serializable
import ksl.utilities.column
import ksl.utilities.isAllDifferent
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.*

@Serializable
data class ProbPoint(
    val value: Double,
    val prob: Double,
    val cumProb: Double
) {
    init {
        require(!(prob < 0.0 || prob > 1.0)) { "Probability must be in interval [0,1]" }
        require(!(cumProb < 0.0 || cumProb > 1.0)) { "Probability must be in interval [0,1]" }
    }

    override fun toString(): String {
        return "ProbPoint(value=$value, prob=$prob, cumProb=$cumProb)${System.lineSeparator()}"
    }
}

/**
 *  Returns the probabilities as an array
 */
fun List<ProbPoint>.probabilities() : DoubleArray {
    return DoubleArray(this.size){ i -> this[i].prob}
}

/**
 *  Returns the values as an array
 */
fun List<ProbPoint>.values() : DoubleArray {
    return DoubleArray(this.size){ i -> this[i].value}
}

/**
 *  Returns the cumulative distribution probabilities as an array
 */
fun List<ProbPoint>.cdf() : DoubleArray {
    return DoubleArray(this.size){ i -> this[i].cumProb}
}

/**
 * This class provides a representation for a discrete distribution with
 * arbitrary values and assigned probabilities to each value.
 * Allows the specification of the distribution via a pair of arrays containing the
 * values = {v1, v2, ..., vn} and the cumulative probabilities cdf = {c1, c2, ..., 1.0}
 *
 * where if p1 is the probability associated with v1, p2 with v2, etc.
 * then c1 = p1, c2 = p1 + p2, c3 = p1 + p2 + p3, etc.,
 * with cn = 1.0 (the sum of all the probabilities). If cn is not 1.0, then
 * an exception is thrown.
 * @param values an array of values that will be drawn from, which must have distinct values
 * @param cdf    a cdf corresponding to the values
 * @param name   an optional name/label
 */
class DEmpiricalCDF(values: DoubleArray, cdf: DoubleArray, name: String? = null) :
    Distribution(name), DiscreteDistributionIfc, GetRVariableIfc, RVParametersTypeIfc by RVType.DEmpirical {

        @Suppress("unused")
        constructor(
            data: List<ProbPoint>,
            name: String? = null
        ): this(data.probabilities(), data.cdf(), name)

    /**
     * Holds the list of probability points
     */
    private val myProbabilityPoints = mutableListOf<ProbPoint>()
    @Suppress("unused")
    val probabilityPoints: List<ProbPoint>
        get() = myProbabilityPoints

    init {
        require(values.size == cdf.size) { "The values array was not the same size as the CDF array1" }
        require(KSLRandom.isValidCDF(cdf)) { "The supplied CDF array was not a CDF!" }
        require(values.isAllDifferent()) { "The supplied array of values did not have distinct values!" }
        val pmf = KSLRandom.makePMF(cdf)
        for (i in values.indices) {
            myProbabilityPoints.add(ProbPoint(values[i], pmf[i], cdf[i]))
        }
    }

    val values: DoubleArray
        get() {
            val valArr = DoubleArray(myProbabilityPoints.size)
            for (i in myProbabilityPoints.indices) {
                valArr[i] = myProbabilityPoints[i].value
            }
            return valArr
        }

    val probPoints: DoubleArray
        get() {
            val valArr = DoubleArray(myProbabilityPoints.size)
            for (i in myProbabilityPoints.indices) {
                valArr[i] = myProbabilityPoints[i].prob
            }
            return valArr
        }

    val cdf: DoubleArray
        get() {
            val cdfArr = DoubleArray(myProbabilityPoints.size)
            for (i in myProbabilityPoints.indices) {
                cdfArr[i] = myProbabilityPoints[i].cumProb
            }
            return cdfArr
        }

    override fun instance(): DEmpiricalCDF {
        return DEmpiricalCDF(values, cdf)
    }

    override fun cdf(x: Double): Double {
        var lowpt = myProbabilityPoints.first()
        if (x < lowpt.value) {
            return 0.0
        }
        var uppt = myProbabilityPoints.last()
        if (x >= uppt.value) {
            return 1.0
        }
        val iter: ListIterator<ProbPoint> = myProbabilityPoints.listIterator()
        while (iter.hasNext()) {
            lowpt = iter.next()
            uppt = iter.next()
            val lv = lowpt.value
            val uv = uppt.value
            if (lv <= x && x < uv) {
                break
            }
        }
        return lowpt.cumProb
    }

    override fun mean(): Double {
        var m = 0.0
        for (p in myProbabilityPoints) {
            m = m + p.prob * p.value
        }
        return m
    }

    override fun variance(): Double {
        var m1 = 0.0
        var m2 = 0.0
        for (pp in myProbabilityPoints) {
            val v = pp.value
            val p = pp.prob
            m1 = m1 + p * v
            m2 = m2 + p * v * v
        }
        return m2 - m1 * m1
    }

    /**
     * The probability mass function for this discrete distribution.
     * Returns the same as PDF.
     *
     * @param x The point to get the probability for
     * @return The probability associated with x
     */
    override fun pmf(x: Double): Double {
        for(pp in myProbabilityPoints) {
            if (pp.value == x){
                return pp.prob
            }
        }
        // went through all values and not found
        return 0.0
    }

    override fun pmf(i: Int): Double {
        for(pp in myProbabilityPoints) {
            if (pp.value == i.toDouble()){
                return pp.prob
            }
        }
        // went through all values and not found
        return 0.0
    }

    /**
     * Returns the pmf as a string.
     *
     * @return A String of probability, value pairs.
     */
    override fun toString(): String {
        return "DEmpirical(${myProbabilityPoints.joinToString()})"
    }

    /**
     * Provides the inverse cumulative distribution function for the
     * distribution
     *
     * @param p The probability to be evaluated for the inverse, p must be [0,1]
     * or
     * an IllegalArgumentException is thrown
     * @return The inverse cdf evaluated at p
     */
    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Probability must be [0,1]" }
        var x = 0.0
        val iter: ListIterator<ProbPoint> = myProbabilityPoints.listIterator()
        while (iter.hasNext()) {
            val pp = iter.next()
            val cp = pp.cumProb
            if (p <= cp) {
                x = pp.value
                break
            }
        }
        return x
    }

    /**
     * Sets the parameters for the distribution. Array of probability points
     * (value, cumulative probability), e.g., X[] = [v1, cp1, v2, cp2, ..., vn, cpn],
     * as the input parameters.
     *
     * @param params an array of doubles representing the parameters for
     * the distribution
     */
    override fun parameters(params: DoubleArray) {
        require(params.size % 2 == 0) { "Input probability array does not have an even number of elements" }
        require(params[params.size - 1] == 1.0) { "CDF must sum to 1.0, last prob was not 1.0" }
        val splitParamArr = splitParameterArray(params)
        splitParamArr.column(0)
        val valArray = splitParamArr.column(0)
        require(valArray.isAllDifferent()) { "The values in the supplied parameters array were not distinct!" }
        val cdfArray = splitParamArr.column(1)
        require(KSLRandom.isValidCDF(cdfArray)) { "The supplied CDF in the parameters array was not a CDF!" }
        val pmf = KSLRandom.makePMF(cdfArray)
        myProbabilityPoints.clear()
        for (i in valArray.indices) {
            myProbabilityPoints.add(ProbPoint(valArray[i], pmf[i], cdfArray[i]))
        }
    }

    /**
     * Gets the parameters for the distribution as an array of paired parameters
     * (value, cumulative probability), e.g., X[] = [v1, cp1, v2, cp2, ..., vn, cpn].
     *
     * @return Returns an array of the parameters for the distribution
     */
    override fun parameters(): DoubleArray {
        val n = 2 * myProbabilityPoints.size
        val param = DoubleArray(n)
        var i = 0
        var p: ProbPoint
        val iter: ListIterator<ProbPoint> = myProbabilityPoints.listIterator()
        while (iter.hasNext()) {
            p = iter.next()
            param[i] = p.value
            param[i + 1] = p.cumProb
            i = i + 2
        }
        return param
    }

    override fun randomVariable(streamNumber: Int, streamProvider: RNStreamProviderIfc): DEmpiricalRV {
        val values = DoubleArray(myProbabilityPoints.size)
        val cdf = DoubleArray(myProbabilityPoints.size)
        var i = 0
        for (probPoint in myProbabilityPoints) {
            values[i] = probPoint.value
            cdf[i] = probPoint.cumProb
            i++
        }
        return DEmpiricalRV(values, cdf, streamNumber, streamProvider)
    }

    companion object {

        /**
         *  Creates a DEmpirical based on a probability mass function
         */
        fun makeDEmpirical(range: IntRange, pmf: PMFIfc) : DEmpiricalCDF {
            val vp = pmf.pmf(range)
            val values = vp.keys.toList()
            val cdfArray = KSLRandom.makeCDF(vp.values.toDoubleArray())
            val valueArray = DoubleArray(vp.size){ values[it].toDouble()}
            return DEmpiricalCDF(valueArray, cdfArray)
        }

        /**
         * Assigns the probability associated with each cdf value
         * to the integers starting at 0.
         *
         * @param cdf the probability array. Must have valid probability elements
         * and the last element must be equal to 1. Every element must be greater than or equal
         * to the previous element. That is, monotonically increasing.
         * @return the pairs
         */
        @Suppress("unused")
        fun makeParameterArray(cdf: DoubleArray): DoubleArray {
            return makeParameterArray(0, cdf)
        }

        /**
         * Assigns the probability associated with each cdf value
         * with the integers starting at the start value.
         *
         * @param start place to start the assignment
         * @param cdf   the probability array. Must have valid probability elements
         * and the last element must be equal to 1. Every element must be greater than or equal
         * to the previous element. That is, monotonically increasing.
         * @return the pairs as an array[] = {v1, cp1, v2, cp2, ...},
         */
        fun makeParameterArray(start: Int, cdf: DoubleArray): DoubleArray {
            require(KSLRandom.isValidCDF(cdf)) { "The cdf array was not a valid CDF" }
            val pairs = DoubleArray(cdf.size * 2)
            var v = start
            for (i in cdf.indices) {
                pairs[2 * i] = v.toDouble()
                pairs[2 * i + 1] = cdf[i]
                v = v + 1
            }
            return pairs
        }

        /**
         * This method takes in an Array of probability points
         * (value, cumulative probability), e.g., X[] = {v1, cp1, v2, cp2, ...},
         * as the input parameter and makes a 2D array of the value/prob pairs
         *
         * @param pairs An array holding the value, cumulative probability pairs.
         */
        fun splitParameterArray(pairs: DoubleArray): Array<DoubleArray> {
            val n = pairs.size / 2
            val split = Array(2) { DoubleArray(n) }
            for (i in 0 until n) {
                split[0][i] = pairs[2 * i]
                split[1][i] = pairs[2 * i + 1]
            }
            return split
        }

        /**
         * Makes a pair array that can be used for the parameters of the DEmpiricalCDF distribution
         *
         * @param values an array of values that will be drawn from
         * @param cdf    a cdf corresponding to the values
         * @return a properly configured array of pairs for the DEmpiricalCDF distribution
         */
        @Suppress("unused")
        fun makeParameterArray(values: DoubleArray, cdf: DoubleArray): DoubleArray {
            require(values.size == cdf.size) { "The values array was not the same size as the CDF array1" }
            require(KSLRandom.isValidCDF(cdf)) { "The supplied CDF array was not a CDF!" }
            require(values.isAllDifferent()) { "The supplied array of values did not have distinct values!" }
            val pairs = DoubleArray(cdf.size * 2)
            for (i in cdf.indices) {
                pairs[2 * i] = values[i]
                pairs[2 * i + 1] = cdf[i]
            }
            return pairs
        }

    }
}

fun main() {
    var values = doubleArrayOf(1.0, 2.0, 3.0, 4.0)

    println("size of values = ${values.size}")
    println("size of distinct values = ${values.distinct().size}")
    values.distinct().forEach(::println)

    var cdf = doubleArrayOf(1.0 / 6.0, 3.0 / 6.0, 5.0 / 6.0, 1.0)
    val n2 = DEmpiricalCDF(values, cdf)
    val rv2 = n2.randomVariable()
    println("mean = " + n2.mean())
    println("var = " + n2.variance())
    println("pmf")
    println(n2)
    for (i in 1..10) {
        println("x(" + i + ")= " + rv2.value)
    }
    values = doubleArrayOf(1.0, 2.0, 4.0, 5.0)
    cdf = doubleArrayOf(0.7, 0.8, 0.9, 1.0)
    val d = DEmpiricalCDF(values, cdf)
    val rvd = d.randomVariable()
    println("mean = " + d.mean())
    println("var = " + d.variance())
    println("pmf")
    println(d)
    for (i in 1..5) {
        println("x(" + i + ")= " + rvd.value)
    }
    println()
    println("invCDF(0.2) = " + d.invCDF(0.2))
    println("invCDF(0.983) = " + d.invCDF(0.983))
    println("invCDF(" + d.cdf(1.0) + ") = " + d.invCDF(d.cdf(1.0)))
    for(i in 1..10){
        val x = i/2.0
        println("pmf($x) = ${d.pmf(x)}")
    }
    println("done")
}