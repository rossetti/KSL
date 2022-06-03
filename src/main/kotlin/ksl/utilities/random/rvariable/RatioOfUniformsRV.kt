package ksl.utilities.random.rvariable

import ksl.utilities.distributions.PDFIfc
import ksl.utilities.random.rng.RNStreamIfc


/**
 * Provides a framework for generating random variates using the
 * ratio of uniforms method.
 * Specifies the pair (u, v), with ratio v/u
 *
 * @param umax the maximum bound in the "u" variate
 * @param vmin the minimum bound for the "v" variate
 * @param vmax the maximum bound in the "v" variate
 * @param f the desired PDF
 * @param rnStream the random number stream to use
 */
class RatioOfUniformsRV (
    umax: Double,
    vmin: Double,
    vmax: Double,
    f: PDFIfc,
    rnStream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(rnStream) {
    private val uCDF: UniformRV = UniformRV(0.0, umax, rnStream)
    private val vCDF: UniformRV = UniformRV(vmin, vmax, rnStream)
    private val pdf: PDFIfc = f

    override fun generate(): Double {
        while (true) {
            val u = uCDF.value
            val v = vCDF.value
            val z = v / u
            if (u * u < pdf.pdf(z)) {
                return z
            }
        }
    }

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return RatioOfUniformsRV(uCDF.max, vCDF.min, vCDF.max, pdf, stream)
    }
}