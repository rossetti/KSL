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

import ksl.utilities.Interval
import ksl.utilities.distributions.fitting.AndersonDarlingScoringModel
import ksl.utilities.distributions.fitting.ChiSquaredScoringModel
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.PearsonType5MLEParameterEstimator
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.PearsonType5RV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.U01Test
import kotlin.math.exp
import kotlin.math.pow

/** Represents a Pearson Type V distribution,
 * see Law (2007) Simulation Modeling and Analysis, McGraw-Hill, pg 293
 *
 * @param shape must be &gt;0
 * @param scale must be &gt; 0
 * @param name an optional label/name
 */
class PearsonType5 (shape: Double = 1.0, scale: Double = 1.0, name: String? = null) :
    Distribution<PearsonType5>(name), ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc {

    init {
        require(shape > 0) { "Alpha (shape parameter) should be > 0" }
        require(scale > 0) { "Beta (scale parameter) should > 0" }
    }
    /** Gets the shape parameter
     *
     * @return the shape parameter
     */
    var shape = shape
        private set

    /** Gets the scale parameter
     *
     * @return the scale parameter
     */
    var scale = scale
        private set

    private var myGammaCDF: Gamma = Gamma(this.shape, 1.0 / this.scale)

    private var myGAlpha = Gamma.gammaFunction(this.shape)

    /** Creates a PearsonType5 distribution
     * parameters[0] = shape
     * parameters[1] = scale
     *
     * @param parameters the parameter array
     */
    constructor(parameters: DoubleArray) : this(parameters[0], parameters[1], null)

    override fun instance(): PearsonType5 {
        return PearsonType5(shape, scale)
    }

    /** Sets the shape and scale parameters
     *
     * @param shape must be &gt; 0
     * @param scale must be &gt; 0
     */
    fun setParameters(shape: Double, scale: Double) {
        require(shape > 0) { "Alpha (shape parameter) should be > 0" }
        require(scale > 0) { "Beta (scale parameter) should > 0" }
        this.shape = shape
        myGAlpha = Gamma.gammaFunction(shape)
        this.scale = scale
        myGammaCDF.shape = shape
        myGammaCDF.scale = 1.0 / scale
    }

    override fun domain(): Interval {
        return Interval(0.0, Double.POSITIVE_INFINITY)
    }

    override fun cdf(x: Double): Double {
        if ((x == Double.POSITIVE_INFINITY) || (x == Double.MAX_VALUE)) {
            return 1.0 - Double.MIN_VALUE
        }
        if (x > 0.0) {
            val f = 1.0 - myGammaCDF.cdf(1.0 / x)
            // the accuracy of the gamma computation may cause value outside (0,1)
            if (f >= 1.0){
                return 1.0 - Double.MIN_VALUE
            }
            if (f <= 0.0){
                return Double.MIN_VALUE
            }
            return f
        } else {
            return 0.0
        }
    }

    /**
     *
     * @return If shape &lt;= 1.0, returns Double.NaN, otherwise, returns the mean
     */
    override fun mean(): Double {
        return if (shape <= 1.0) {
            Double.NaN
        } else scale / (shape - 1.0)
    }

    /** Gets the parameters
     *
     * parameters[0] = shape
     * parameters[1] = scale
     *
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(shape, scale)
    }

    /**
     *
     * @return If shape &lt;= 2.0, returns Double.NaN, otherwise returns the variance
     */
    override fun variance(): Double {
        return if (shape <= 2.0) {
            Double.NaN
        } else scale * scale / ((shape - 2.0) * (shape - 1.0) * (shape - 1.0))
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Probability must be [0,1]" }
        return 1.0 / myGammaCDF.invCDF(1.0 - p)
    }

    override fun pdf(x: Double): Double {
        return if (x > 0.0) {
            x.pow(-(shape + 1.0)) * exp(-scale / x) / (scale.pow(-shape) * myGAlpha)
        } else 0.0
    }

    /** Sets the parameters of the distribution
     *
     * parameters[0] = shape
     * parameters[1] = scale
     *
     * @param params the parameter array
     */
    override fun parameters(params: DoubleArray) {
        setParameters(params[0], params[1])
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return PearsonType5RV(shape, scale, stream)
    }

    override fun toString(): String {
        return "PearsonType5(shape=$shape, scale=$scale)"
    }

}

fun main(){

    val d = PearsonType5(shape = 0.40677819273863525, scale = 2.816753701768571)
   // val rv = d.randomVariable

    val e = ExponentialRV(10.0)
    //   val se = ShiftedRV(5.0, e)
    val n = 1000
    val data = e.sample(n)
    println(Statistic(data))
    println()

    val pe = PearsonType5MLEParameterEstimator()
    val result = pe.estimate(data, Statistic(data))
    println(result)
   // val sm = ChiSquaredScoringModel()
    val cdf = PDFModeler.createDistribution(result.parameters!!)
    println(cdf)

    val p = U01Test.recommendedU01BreakPoints(1000, PDFModeler.defaultConfidenceLevel)
    println()
    println(p.joinToString())

    var bp = PDFModeler.equalizedCDFBreakPoints(data.size, cdf!!)

    println()
    println(bp.joinToString())

    val sm = AndersonDarlingScoringModel()

    sm.score(data, result.parameters!!)

}