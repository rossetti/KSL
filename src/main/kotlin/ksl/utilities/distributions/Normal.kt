package ksl.utilities.distributions

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class Normal {

    companion object {
        private val baseNorm = sqrt(2.0 * PI)

        private const val errorFunctionConstant = 0.2316419

        private val coeffs = doubleArrayOf(0.31938153, -0.356563782, 1.781477937, -1.821255978, 1.330274429)

        private val a = doubleArrayOf(
            -3.969683028665376e+01, 2.209460984245205e+02,
            -2.759285104469687e+02, 1.383577518672690e+02,
            -3.066479806614716e+01, 2.506628277459239e+00
        )

        private val b = doubleArrayOf(
            -5.447609879822406e+01, 1.615858368580409e+02,
            -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01
        )

        private val c = doubleArrayOf(
            -7.784894002430293e-03, -3.223964580411365e-01,
            -2.400758277161838e+00, -2.549732539343734e+00,
            4.374664141464968e+00, 2.938163982698783e+00
        )

        private val d = doubleArrayOf(
            7.784695709041462e-03, 3.224671290700398e-01,
            2.445134137142996e+00, 3.754408661907416e+00
        )

        /** Computes the cumulative distribution function for a standard
         * normal distribution
         * from Abramovitz  and Stegun, see also Didier H. Besset
         * Object-oriented Implementation of Numerical Methods, Morgan-Kaufmann (2001)
         *
         * @param z the z-ordinate to be evaluated
         * @return the P(Z&lt;=z) for standard normal
         */
        fun stdNormalCDFAbramovitzAndStegun(z: Double): Double {
            if (z == 0.0) {
                return 0.5
            } else if (z > 0) {
                return 1 - stdNormalCDFAbramovitzAndStegun(-z)
            }
            val t = 1 / (1 - errorFunctionConstant * z)
            val phi =
                coeffs[0] + t * (coeffs[1] + t * (coeffs[2] + t * (coeffs[3] + t * coeffs[4])))
            return t * phi * stdNormalPDF(z)
        }

        /** Computes the cumulative distribution function for a standard
         * normal distribution using Taylor approximation.
         *
         * The approximation is accurate to absolute error less than 8 * 10^(-16).
         * *  Reference: Evaluating the Normal Distribution by George Marsaglia.
         * *  http://www.jstatsoft.org/v11/a04/paper
         *
         * @param z the z-ordinate to be evaluated
         * @return the P(Z&lt;=z) for standard normal
         */
        fun stdNormalCDF(z: Double): Double {
            if (z < -8.0) return 0.0
            if (z > 8.0) return 1.0
            var sum = 0.0
            var term = z
            var i = 3
            while (sum + term != sum) {
                sum = sum + term
                term = term * z * z / i
                i += 2
            }
            return 0.5 + sum * stdNormalPDF(z)
        }

        /** Computes the pdf function for a standard normal distribution
         * from Abramovitz and Stegun, see also Didier H. Besset
         * Object-oriented Implementation of Numerical Methods, Morgan-Kaufmann (2001)
         *
         * @param z the z-ordinate to be evaluated
         * @return the f(z) for standard normal
         */
        fun stdNormalPDF(z: Double): Double {
            return exp(-0.5 * z * z) / baseNorm
        }

        /** Computes the inverse cumulative distribution function for a standard
         * normal distribution
         * see, W. J. Cody, Rational Chebyshev approximations for the error function
         * Math. Comp. pp 631-638
         * this is without the extra refinement and has relative error of 1.15e-9
         * http://www.math.uio.no/~jacklam/notes/invnorm/
         * @param p the probability to be evaluated, p must be within [0,1]
         * p = 0.0 returns Double.NEGATIVE_INFINTITY
         * p = 1.0 returns Double.POSITIVE_INFINITY
         * @return the "z" value associated with the p
         */
        fun stdNormalInvCDF(p: Double): Double {
            require(!(p < 0.0 || p > 1.0)) { "Supplied probability was $p Probability must be (0,1)" }
            if (p <= 0.0) {
                return Double.NEGATIVE_INFINITY
            }
            if (p >= 1.0) {
                return Double.POSITIVE_INFINITY
            }

            // define the breakpoints
            val plow = 0.02425
            val phigh = 1 - plow
            var r = 0.0
            var q = 0.0
            var z = 0.0
            var x = 0.0
            var y = 0.0
            if (p < plow) { // rational approximation for the lower region
                q = sqrt(-2 * ln(p))
                x =
                    ((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]
                y = (((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1
                z = x / y
                return z
            }
            if (phigh < p) { // rational approximation for upper region
                q = sqrt(-2 * ln(1.0 - p))
                x =
                    ((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]
                y = (((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1
                z = -x / y
                return z
            }

            // rational approximation for central region
            q = p - 0.5
            r = q * q
            x =
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
            y = ((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1
            z = x / y
            return z
        }

        /** Computes the complementary cumulative probability for the standard normal
         * distribution function for given value of z
         * @param z The value to be evaluated
         * @return The probability, 1-P{X&lt;=z}
         */
        fun stdNormalComplementaryCDF(z: Double): Double {
            return 1.0 - stdNormalCDF(z)
        }

        /** Computes the first order loss function for the standard normal
         * distribution function for given value of x, G1(z) = E[max(Z-z,0)]
         * @param z The value to be evaluated
         * @return The loss function value, E[max(Z-z,0)]
         */
        fun stdNormalFirstOrderLossFunction(z: Double): Double {
            return -z * stdNormalComplementaryCDF(z) + stdNormalPDF(z)
        }

        /** Computes the 2nd order loss function for the standard normal
         * distribution function for given value of z, G2(z) = (1/2)E[max(Z-z,0)*max(Z-z-1,0)]
         * @param z The value to be evaluated
         * @return The loss function value, (1/2)E[max(Z-z,0)*max(Z-z-1,0)]
         */
        fun stdNormalSecondOrderLossFunction(z: Double): Double {
            return 0.5 * ((z * z + 1.0) * stdNormalComplementaryCDF(z) - z * stdNormalPDF(z))
        }

    }
}