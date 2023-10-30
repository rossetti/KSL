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

package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.ChiSquaredDistribution
import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.Normal
import ksl.utilities.io.KSL
import ksl.utilities.math.KSLMath
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.HistogramIfc
import ksl.utilities.statistic.Statistic
import kotlin.math.*


abstract class DistributionGOF(
    protected val data: DoubleArray,
    final override val numEstimatedParameters: Int = 1,
    breakPoints: DoubleArray
) : DistributionGOFIfc {

    init {
        require(numEstimatedParameters >= 0) { "The number of estimated parameters must be >= 0" }
    }

    final override val histogram: HistogramIfc = Histogram.create(data, breakPoints)

    final override val breakPoints = histogram.breakPoints

    final override val binCounts = histogram.binCounts

    final override val chiSquaredTestDOF = histogram.numberBins - 1 - numEstimatedParameters

    final override val chiSquaredTestStatistic
        get() = Statistic.chiSqTestStatistic(binCounts, expectedCounts)

    final override val chiSquaredPValue: Double
        get() {
            val chiDist = ChiSquaredDistribution(chiSquaredTestDOF.toDouble())
            return chiDist.complementaryCDF(chiSquaredTestStatistic)
        }

    companion object {
        private const val DBL_EPSILON: Double = 2.2204460492503131E-16
        private const val RAC2: Double = 1.41421356237309504880 // sqrt(2.0)
        private const val XBIG = 100.0        // x infinity for some distributions

        /**
         *  A port of the Cramer-Von-Mises cumulative distribution function
         *  based on the implementation in [SSJ](https://github.com/umontreal-simul/ssj).
         */
        fun cramerVonMisesCDF(n: Int, x: Double): Double {
            require(n > 0) { "n <= 0" }

            if (n == 1) {
                if (x <= 1.0 / 12.0) return 0.0
                return if (x >= 1.0 / 3.0) 1.0 else 2.0 * sqrt(x - 1.0 / 12.0)
            }
            if (x <= 1.0 / (12.0 * n)) return 0.0
            if (x <= (n + 3.0) / (12.0 * n * n)) {
                val t: Double = KSLMath.logFactorial(n) - Gamma.logGammaFunction(1.0 + 0.5 * n) +
                        0.5 * n * ln(Math.PI * (x - 1.0 / (12.0 * n)))
                return exp(t)
            }
            if (x <= 0.002) return 0.0
            if (x > 3.95 || x >= n / 3.0) return 1.0
            val EPSILON: Double = DBL_EPSILON
            val JMAX = 20
            var j = 0
            val Cor: Double
            var Res: Double
            var arg: Double
            val termX: Double
            var termS: Double
            var termJ: Double
            termX = 0.0625 / x // 1 / (16x)
            Res = 0.0
            val A = doubleArrayOf(
                1.0,
                1.11803398875,
                1.125,
                1.12673477358,
                1.1274116945,
                1.12774323743,
                1.1279296875,
                1.12804477649,
                1.12812074678,
                1.12817350091
            )
            do {
                termJ = (4 * j + 1).toDouble()
                arg = termJ * termJ * termX
                termS = A[j] * exp(-arg) * besselK025(arg)
                Res += termS
                ++j
            } while (!(termS < EPSILON || j > JMAX))
            if (j > JMAX) {
                KSL.logger.warn { "cramerVonMises: iterations have not converged" }
            }

            Res /= Math.PI * sqrt(x)

            // Empirical correction in 1/n
            Cor =
                if (x < 0.0092) 0.0 else if (x < 0.03) -0.0121763 + x * (2.56672 - 132.571 * x) else if (x < 0.06) 0.108688 + x * (-7.14677 + 58.0662 * x) else if (x < 0.19) -0.0539444 + x * (-2.22024 + x * (25.0407 - 64.9233 * x)) else if (x < 0.5) -0.251455 + x * (2.46087 + x * (-8.92836 + x * (14.0988 -
                        x * (5.5204 + 4.61784 * x)))) else if (x <= 1.1) 0.0782122 + x * (-0.519924 + x * (1.75148 +
                        x * (-2.72035 + x * (1.94487 - 0.524911 * x)))) else exp(-0.244889 - 4.26506 * x)
            Res += Cor / n
            // This empirical correction is not very precise, so ...
            return if (Res <= 1.0) Res else 1.0
        }

        /**
         * Returns the value of the modified Bessel’s function of the second kind.
         * @param x value at which the function is calculated
         * @return the value of the function
         */
        private fun besselK025(x: Double): Double {
            if (x.isNaN()) return Double.NaN
            if (x < 1e-300) return Double.MIN_VALUE
            val DEG = 6
            val c = doubleArrayOf(
                32177591145.0,
                2099336339520.0,
                16281990144000.0,
                34611957596160.0,
                26640289628160.0,
                7901666082816.0,
                755914244096.0
            )
            val b = doubleArrayOf(
                75293843625.0,
                2891283595200.0,
                18691126272000.0,
                36807140966400.0,
                27348959232000.0,
                7972533043200.0,
                755914244096.0
            )

            /*------------------------------------------------------------------
       * x > 0.6 => approximation asymptotique rationnelle dans Luke:
       * Yudell L.Luke "Mathematical functions and their approximations",
       * Academic Press Inc. New York, 1975, p.371
       *------------------------------------------------------------------*/

            if (x >= 0.6) {
                var B = b[DEG]
                var C = c[DEG]
                for (j in DEG downTo 1) {
                    B = B * x + b[j - 1]
                    C = C * x + c[j - 1]
                }
                return sqrt(Math.PI / (2.0 * x)) * exp(-x) * (C / B)
            }

            /*------------------------------------------------------------------
       * x < 0.6 => la serie de K_{1/4} = Pi/Sqrt (2) [I_{-1/4} - I_{1/4}]
       *------------------------------------------------------------------*/
            val xx = x * x
            val rac: Double = (x / 2.0).pow(0.25)
            var Res = (((xx / 1386.0 + 1.0 / 42.0) * xx + 1.0 / 3.0) * xx + 1.0) /
                    (1.225416702465177 * rac)
            val temp = (((xx / 3510.0 + 1.0 / 90.0) * xx + 0.2) * xx + 1.0) * rac /
                    0.906402477055477
            Res = Math.PI * (Res - temp) / RAC2
            return Res
        }

        /**
         * Computes the *Anderson–Darling* distribution function at [x], with
         * parameter [n], using Marsaglia’s et al. algorithm.
         * A port based on the implementation in [SSJ](https://github.com/umontreal-simul/ssj).
         */
        fun andersonDarlingCDF(n: Int, x: Double): Double {
            require(n > 0) { "n <= 0" }
            if (x <= 0) return 0.0
            if (x >= XBIG) return 1.0
            if (1 == n) return cdfN1(x)
            val RES: Double = internalAndersonDarling(n, x, true)
            return if (RES <= 0.0) 0.0 else RES
        }

        private fun cdfN1(x: Double): Double {
            // The Anderson-Darling distribution for N = 1
            val AD_X0 = 0.38629436111989062
            val AD_X1 = 37.816242111357
            if (x <= AD_X0) return 0.0
            return if (x >= AD_X1) 1.0 else sqrt(1.0 - 4.0 * exp(-x - 1.0))
        }

        private fun internalAndersonDarling(n: Int, z: Double, isFastADinf: Boolean): Double {
            var v: Double
            //If isFastADinf is true, use the fast approximation adinf (z),
            //if it is false, use the more exact ADinf (z)
            val x: Double = if (isFastADinf) adinf(z) else ADinf(z)
            // now x=adinf(z). Next, get v=errfix(n,x) and return x+v;
            if (x > .8) {
                v = (-130.2137 + (745.2337 - (1705.091 - (1950.646 - (1116.360 -
                        255.7844 * x) * x) * x) * x) * x) / n
                return x + v
            }
            val C = .01265 + .1757 / n
            if (x < C) {
                v = x / C
                v = sqrt(v) * (1.0 - v) * (49 * v - 102)
                return x + v * (.0037 / (n * n) + .00078 / n + .00006) / n
            }
            v = (x - C) / (.8 - C)
            v = -.00022633 + (6.54034 - (14.6538 - (14.458 - (8.259 -
                    1.91864 * v) * v) * v) * v) * v
            return x + v * (.04213 + .01365 / n) / n
        }

        private fun adinf(z: Double): Double {
            // max |error| < .000002 for z<2, (p=.90816...)
            // max |error|<.0000008 for 4<z<infinity
            return if (z < 2.0) exp(-1.2337141 / z) / sqrt(z) * (2.00012 + (.247105 -
                    (.0649821 - (.0347962 - (.011672 - .00168691 * z) * z) * z) * z) * z) else
                exp(
                    -exp(
                        1.0776 - (2.30695 - (.43424 - (.082433 -
                                (.008056 - .0003146 * z) * z) * z) * z) * z
                    )
                )
        }

        private fun ADinf(z: Double): Double {
            if (z < .01) return 0.0 // avoids exponent limits; ADinf(.01)=.528e-52
            var ad: Double
            var adnew: Double
            var r: Double = 1.0 / z
            ad = r * ADf(z, 0)
            var j: Int = 1
            while (j < 100) {
                r *= (.5 - j) / j
                adnew = ad + (4 * j + 1) * r * ADf(z, j)
                if (ad == adnew) {
                    return ad
                }
                ad = adnew
                j++
            }
            return ad
        }

        private fun ADf(z: Double, j: Int): Double {
            // called by ADinf(); see article.
            val T = (4.0 * j + 1.0) * (4.0 * j + 1.0) * 1.23370055013617 / z
            if (T > 150.0) return 0.0
            var f: Double
            var fnew: Double
            var c: Double
            var a = 2.22144146907918 * exp(-T) / sqrt(T)
            // initialization requires cPhi
            // if you have erfc(), replace 2*cPhi(sqrt(2*t)) with erfc(sqrt(t))
            var b = 3.93740248643060 * 2.0 * Normal.stdNormalCDF(-sqrt(2 * T))
            var r: Double = z * .125
            f = a + b * r
            var i: Int = 1
            while (i < 200) {
                c = ((i - .5 - T) * b + T * a) / i
                a = b
                b = c
                r *= z / (8 * i + 8)
                if (abs(r) < 1e-40 || abs(c) < 1e-40) return f
                fnew = f + c * r
                if (f == fnew) return f
                f = fnew
                i++
            }
            return f
        }

//        private val YWA = doubleArrayOf(
//            1.8121832847E-39, 2.0503176304E-32, 4.6139577764E-27, 6.5869745929E-23, 1.2765816107E-19,
//            5.6251923105E-17, 8.0747150511E-15, 4.8819994144E-13, 1.4996052497E-11, 2.6903519441E-10, 3.1322929018E-9,
//            2.5659643046E-8, 1.5749759318E-7, 7.6105096466E-7, 3.0113293541E-6, 1.0070166837E-5, 2.9199826692E-5,
//            7.4970409372E-5, 1.7340586581E-4, 3.6654236297E-4, 7.165864865E-4, 1.3087767385E-3, 2.2522044209E-3,
//            3.6781862572E-3, 5.7361958631E-3, 8.5877444706E-3, 1.23988738E-2, 1.73320516E-2, 2.35382479E-2,
//            3.11498548E-2, 4.02749297E-2, 5.09930445E-2, 6.33528333E-2, 7.73711747E-2, 9.30338324E-2,
//            1.10297306E-1, 1.290916098E-1, 1.493236984E-1, 1.708812741E-1, 1.936367476E-1, 2.174511609E-1,
//            2.42177928E-1, 2.676662852E-1, 2.937643828E-1, 3.203219784E-1, 3.471927188E-1, 3.742360163E-1,
//            4.013185392E-1, 4.283153467E-1, 4.551107027E-1, 4.815986082E-1, 5.076830902E-1, 5.332782852E-1,
//            5.583083531E-1, 5.827072528E-1, 6.064184099E-1, 6.293943006E-1, 6.515959739E-1, 6.729925313E-1,
//            6.935605784E-1, 7.132836621E-1, 7.321517033E-1, 7.501604333E-1, 7.673108406E-1, 7.836086337E-1,
//            7.99063723E-1, 8.136897251E-1, 8.275034914E-1, 8.405246632E-1, 8.527752531E-1, 8.642792535E-1,
//            8.750622738E-1, 8.851512032E-1, 8.945739017E-1, 9.033589176E-1, 9.115352296E-1, 9.19132015E-1,
//            9.261784413E-1, 9.327034806E-1, 9.387357465E-1, 9.44303351E-1, 9.494337813E-1, 9.541537951E-1,
//            9.584893325E-1, 9.624654445E-1, 9.661062352E-1, 9.694348183E-1, 9.724732859E-1, 9.752426872E-1,
//            9.777630186E-1, 9.800532221E-1, 9.821311912E-1, 9.840137844E-1, 9.85716844E-1, 9.872552203E-1,
//            9.886428002E-1, 9.898925389E-1, 9.910164946E-1, 9.920258656E-1, 9.929310287E-1, 9.937415788E-1,
//            9.944663692E-1, 9.95113552E-1, 9.956906185E-1, 9.962044387E-1, 9.966613009E-1, 9.970669496E-1,
//            9.974266225E-1, 9.977450862E-1, 9.980266707E-1, 9.982753021E-1, 9.984945338E-1, 9.98687576E-1,
//            9.98857324E-1, 9.990063842E-1, 9.991370993E-1, 9.992515708E-1, 9.99351681E-1, 9.994391129E-1,
//            9.995153688E-1, 9.995817875E-1, 9.996395602E-1, 9.996897446E-1, 9.997332791E-1, 9.997709943E-1,
//            9.998036243E-1, 9.998318172E-1, 9.998561438E-1, 9.998771066E-1, 9.998951466E-1, 9.999106508E-1,
//            9.99923958E-1, 9.999353645E-1, 9.999451288E-1, 9.999534765E-1, 9.999606035E-1, 9.999666805E-1,
//            9.999718553E-1, 9.999762562E-1, 9.999799939E-1, 9.999831643E-1, 9.999858E-1, 9.999883E-1
//        )
//
//        private val MWA = doubleArrayOf(
//            0.0, 6.909E-15, 2.763E-14, 1.036E-13, 3.792E-13, 4.773E-12, 4.59E-10, 2.649E-8, 7.353E-7, 1.14E-5,
//            1.102E-4, 7.276E-4, 3.538E-3, 0.01342, 0.04157, 0.1088, 0.2474, 0.4999, 0.913, 1.53, 2.381, 3.475,
//            4.795, 6.3, 7.928, 9.602, 11.24, 12.76, 14.1, 15.18, 15.98, 16.47, 16.64, 16.49, 16.05, 15.35, 14.41,
//            13.28, 12.0, 10.6, 9.13, 7.618, 6.095, 4.588, 3.122, 1.713, 0.3782, -0.8726, -2.031, -3.091, -4.051,
//            -4.91, -5.668, -6.327, -6.893, -7.367, -7.756, -8.064, -8.297, -8.46, -8.56, -8.602, -8.591, -8.533,
//            -8.433, -8.296, -8.127, -7.93, -7.709, -7.469, -7.212, -6.943, -6.663, -6.378, -6.087, -5.795, -5.503,
//            -5.213, -4.927, -4.646, -4.371, -4.103, -3.843, -3.593, -3.352, -3.12, -2.899, -2.689, -2.489, -2.3,
//            -2.121, -1.952, -1.794, -1.645, -1.506, -1.377, -1.256, -1.144, -1.041, -0.9449, -0.8564, -0.775,
//            -0.7001, -0.6315, -0.5687, -0.5113, -0.459, -0.4114, -0.3681, -0.3289, -0.2934, -0.2614, -0.2325,
//            -0.2064, -0.183, -0.1621, -0.1433, -0.1265, -0.1115, -9.813E-2, -8.624E-2, -7.569E-2, -6.632E-2,
//            -5.803E-2, -5.071E-2, -4.424E-2, -3.855E-2, -3.353E-2, -2.914E-2, -2.528E-2, -0.0219, -1.894E-2,
//            -1.637E-2, -1.412E-2, -1.217E-2, -1.046E-2, -8.988E-3, -7.72E-3, -6.567E-3, -5.802E-3, -0.0053,
//            -4.7E-4, -4.3E-4
//        )
//
//        private val CoWA = doubleArrayOf(
//            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.25E-5, 3.87E-5, 1.004E-4, 2.703E-4,
//            6.507E-4, 1.3985E-3, 2.8353E-3, 5.1911E-3, 8.9486E-3, 1.41773E-2, 2.16551E-2, 3.1489E-2, 4.34123E-2,
//            5.78719E-2, 7.46921E-2, 9.45265E-2, 1.165183E-1, 1.406353E-1, 1.662849E-1, 1.929895E-1, 2.189347E-1,
//            2.457772E-1, 2.704794E-1, 2.947906E-1, 3.169854E-1, 3.377435E-1, 3.573555E-1, 3.751205E-1, 3.906829E-1,
//            4.039806E-1, 4.142483E-1, 4.22779E-1, 4.288013E-1, 4.330353E-1, 4.34452E-1, 4.338138E-1, 4.31504E-1,
//            4.272541E-1, 4.220568E-1, 4.158229E-1, 4.083281E-1, 3.981182E-1, 3.871678E-1, 3.755527E-1, 3.628823E-1,
//            3.520135E-1, 3.400924E-1, 3.280532E-1, 3.139477E-1, 2.997087E-1, 2.849179E-1, 2.710475E-1, 2.576478E-1,
//            2.449155E-1, 2.317447E-1, 2.193161E-1, 2.072622E-1, 1.956955E-1, 1.846514E-1, 1.734096E-1, 1.622678E-1,
//            1.520447E-1, 1.416351E-1, 1.32136E-1, 1.231861E-1, 1.150411E-1, 1.071536E-1, 9.9465E-2, 9.22347E-2,
//            8.54394E-2, 7.87697E-2, 7.23848E-2, 6.6587E-2, 6.15849E-2, 5.6573E-2, 5.17893E-2, 4.70011E-2, 4.2886E-2,
//            3.91224E-2, 3.53163E-2, 3.20884E-2, 2.92264E-2, 2.66058E-2, 2.37352E-2, 2.14669E-2, 1.94848E-2,
//            1.75591E-2, 1.58232E-2, 1.40302E-2, 1.24349E-2, 1.11856E-2, 9.9765E-3, 8.9492E-3, 8.0063E-3, 7.1509E-3,
//            6.3196E-3, 5.6856E-3, 5.0686E-3, 4.5085E-3, 3.9895E-3, 3.4804E-3, 3.0447E-3, 2.7012E-3, 2.2984E-3,
//            2.0283E-3, 1.7399E-3, 1.5032E-3, 1.3267E-3, 1.1531E-3, 9.92E-4, 9.211E-4, 8.296E-4, 6.991E-4, 5.84E-4,
//            5.12E-4, 4.314E-4, 3.593E-4, 3.014E-4, 2.401E-4, 2.004E-4, 1.614E-4, 1.257E-4, 1.112E-4, 9.22E-5,
//            8.77E-5, 6.22E-5, 4.93E-5, 3.92E-5, 3.15E-5, 1.03E-5, 9.6E-6
//        )
//
//        /**
//         * Computes the Watson distribution function , with
//         * parameter [n]. A cubic spline interpolation is used for the asymptotic
//         * distribution and an empirical correction.
//         * A port based on the implementation in [SSJ](https://github.com/umontreal-simul/ssj)
//         */
//        fun watsonCDF(n: Int, x: Double): Double {
//            require(n > 1) { "n < 2" }
//            //Approximation of the cumulative distribution function of the
//            // watsonG statistics by the cubic spline function.
//            //   Y[.]  - tabular value of the statistic;
//            //   M[.]  - tabular value of the first derivative;
//            val MINARG = 0.15
//            if (x <= MINARG) return 0.0
//            if (x >= 10.0) return 1.0
//            var R: Double
//            var Res: Double
//            val MAXARG = 1.5
//            if (x > MAXARG) {
//                R = exp(19.0 - 20.0 * x)
//                Res = 1.0 - R
//                // Empirical Correction in 1/sqrt (n)
//                R = exp(13.34 - 15.26 * x) / sqrt(n.toDouble())
//                Res += R
//                // The correction in 1/sqrt (n) is not always precise
//                return if (Res >= 1.0) 1.0 else Res
//            }
//            val MINTAB = 0.1
//            val STEP = 0.01
//            // Search of the correct slot in the interpolation table
//            val i: Int = ((x - MINTAB) / STEP + 1).toInt()
//            val Ti: Double = MINTAB + i * STEP
//            val Tj = Ti - STEP
//            // Approximation within the slot
//            val j: Int = i - 1
//            val H: Double = x - Tj
//            R = Ti - x
//            val P: Double = STEP * STEP / 6.0
//            Res = (MWA[j] * R * R * R + MWA[i] * H * H * H) / 6.0 / STEP
//            Res += ((YWA[j] - MWA[j] * P) * R + (YWA[i] - MWA[i] * P) * H) / STEP
//            // Empirical correction in 1/sqrt (n)
//            Res += (CoWA[i] * H + CoWA[j] * R) / (STEP * sqrt(n.toDouble()))
//            return if (Res >= 1.0) 1.0 else Res
//        }

    }
}