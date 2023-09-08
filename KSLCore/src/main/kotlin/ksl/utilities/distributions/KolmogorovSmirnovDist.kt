/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

import kotlin.math.*

/**
 * A port to Kotlin of the Java implementation provided in:
 *
 *  Simard R, L’Ecuyer P. Computing the Two-Sided Kolmogorov-Smirnov Distribution. J Stat Soft. 2011. ;39(11).
 *  Available from: http://www.jstatsoft.org/v39/i11/
 *
 * <p>Computes both the <i>cumulative</i> probability P[D_n <= x]
 * and the <i>complementary cumulative</i> probability P[D_n >= x] of the
 * 2-sided 1-sample Kolmogorov-Smirnov distribution.</p>
 *
 * The Kolmogorov-Smirnov test statistic D_n is defined by
 * <p>
 *        D_n = sup_x |F(x) - S_n(x)|
 * </p>
 * where n is the sample size, S_n(x) is an empirical distribution function,
 * and F(x) is a completely specified theoretical distribution.
 *
 *
 */
object KolmogorovSmirnovDist {

    private const val num_Ln2 = 0.69314718055994530941 // ln(2)

    private const val PI2 = Math.PI * Math.PI
    private const val PI4 = PI2 * PI2
    private const val NFACT = 20
    private const val MFACT = 30

    /* For x close to 0 or 1, we use the exact formulae of Ruben-Gambino in all
      cases. For n <= NEXACT, we use exact algorithms: the Durbin matrix and
      the Pomeranz algorithms. For n > NEXACT, we use asymptotic methods
      except for x close to 0 where we still use the method of Durbin
      for n <= NKOLMO. For n > NKOLMO, we use asymptotic methods only and
      so the precision is less for x close to 0.
      We could increase the limit NKOLMO to 10^6 to get better precision
      for x close to 0, but at the price of a slower speed. */
    private const val NEXACT = 140
    private const val NKOLMO = 100000

    // For the Durbin matrix algorithm
    private const val NORM = 1.0e140
    private const val INORM = 1.0e-140
    private const val LOGNORM = 140

    //========================================================================
    // The factorial n! for  0 <= n <= NFACT
    private val Factorial = doubleArrayOf(
        1.0,
        1.0,
        2.0,
        6.0,
        24.0,
        120.0,
        720.0,
        5040.0,
        40320.0,
        362880.0,
        3628800.0,
        39916800.0,
        479001600.0,
        6227020800.0,
        87178291200.0,
        1307674368000.0,
        20922789888000.0,
        355687428096000.0,
        6402373705728000.0,
        1.21645100408832e+17,
        2.43290200817664e+18
    )

    //========================================================================
    // The natural logarithm of factorial n! for  0 <= n <= MFACT
    private val LnFactorial = doubleArrayOf(
        0.0,
        0.0,
        0.6931471805599453,
        1.791759469228055,
        3.178053830347946,
        4.787491742782046,
        6.579251212010101,
        8.525161361065415,
        10.60460290274525,
        12.80182748008147,
        15.10441257307552,
        17.50230784587389,
        19.98721449566188,
        22.55216385312342,
        25.19122118273868,
        27.89927138384088,
        30.67186010608066,
        33.50507345013688,
        36.39544520803305,
        39.33988418719949,
        42.33561646075348,
        45.3801388984769,
        48.47118135183522,
        51.60667556776437,
        54.7847293981123,
        58.00360522298051,
        61.26170176100199,
        64.55753862700632,
        67.88974313718154,
        71.257038967168,
        74.65823634883016
    )

    private fun getLogFactorial(n: Int): Double {
        // Returns the natural logarithm of factorial n!
        return if (n <= MFACT) {
            LnFactorial[n]
        } else {
            val x = (n + 1).toDouble()
            val y = 1.0 / (x * x)
            var z = ((-(5.95238095238E-4 * y) + 7.936500793651E-4) * y -
                    2.7777777777778E-3) * y + 8.3333333333333E-2
            z = (x - 0.5) * ln(x) - x + 9.1893853320467E-1 + z / x
            z
        }
    }

    private fun KSPlusbarAsymp(n: Int, x: Double): Double {
        /* Compute the probability of the KS+ distribution using an asymptotic
         formula */
        val t = 6.0 * n * x + 1
        val z = t * t / (18.0 * n)
        var v = 1.0 - (2.0 * z * z - 4.0 * z - 1.0) / (18.0 * n)
        if (v <= 0.0) return 0.0
        v = v * exp(-z)
        return if (v >= 1.0) 1.0 else v
    }

    private fun KSPlusbarUpper(n: Int, x: Double): Double {
        /* Compute the probability of the complementary KS+ distribution in
         the upper tail using Smirnov's stable formula */
        if (n > 200000) return KSPlusbarAsymp(n, x)
        var jmax = (n * (1.0 - x)).toInt()
        // Avoid log(0) for j = jmax and q ~ 1.0
        if (1.0 - x - jmax.toDouble() / n <= 0.0) jmax--
        val jdiv: Int
        jdiv = if (n > 3000) 2 else 3
        var j = jmax / jdiv + 1
        var LogCom = getLogFactorial(n) - getLogFactorial(j) -
                getLogFactorial(n - j)
        val LOGJMAX = LogCom
        val EPSILON = 1.0E-12
        var q: Double
        var term: Double
        var t: Double
        var Sum = 0.0
        while (j <= jmax) {
            q = j.toDouble() / n + x
            term = LogCom + (j - 1) * ln(q) + (n - j) * ln1p(-q)
            t = exp(term)
            Sum += t
            LogCom += ln((n - j).toDouble() / (j + 1))
            if (t <= Sum * EPSILON) break
            j++
        }
        j = jmax / jdiv
        LogCom = LOGJMAX + ln((j + 1).toDouble() / (n - j))
        while (j > 0) {
            q = j.toDouble() / n + x
            term = LogCom + (j - 1) * ln(q) + (n - j) * ln1p(-q)
            t = exp(term)
            Sum += t
            LogCom += ln(j.toDouble() / (n - j + 1))
            if (t <= Sum * EPSILON) break
            j--
        }
        Sum *= x
        // add the term j = 0
        Sum += exp(n * ln1p(-x))
        return Sum
    }

    private fun Pelz(n: Int, x: Double): Double {
        /* Approximating the Lower Tail-Areas of the Kolmogorov-Smirnov One-Sample
         Statistic,
         Wolfgang Pelz and I. J. Good,
         Journal of the Royal Statistical Society, Series B.
             Vol. 38, No. 2 (1976), pp. 152-156
       */
        val JMAX = 20
        val EPS = 1.0e-10
        val C = 2.506628274631001 // sqrt(2*Pi)
        val C2 = 1.2533141373155001 // sqrt(Pi/2)
        val RACN = sqrt(n.toDouble())
        val z = RACN * x
        val z2 = z * z
        val z4 = z2 * z2
        val z6 = z4 * z2
        val w = PI2 / (2.0 * z * z)
        var ti: Double
        var term: Double
        var tom: Double
        var sum: Double
        var j: Int
        term = 1.0
        j = 0
        sum = 0.0
        while (j <= JMAX && term > EPS * sum) {
            ti = j + 0.5
            term = exp(-ti * ti * w)
            sum += term
            j++
        }
        sum *= C / z
        term = 1.0
        tom = 0.0
        j = 0
        while (j <= JMAX && abs(term) > EPS * abs(tom)) {
            ti = j + 0.5
            term = (PI2 * ti * ti - z2) * exp(-ti * ti * w)
            tom += term
            j++
        }
        sum += tom * C2 / (RACN * 3.0 * z4)
        term = 1.0
        tom = 0.0
        j = 0
        while (j <= JMAX && abs(term) > EPS * abs(tom)) {
            ti = j + 0.5
            term = 6 * z6 + 2 * z4 + PI2 * (2 * z4 - 5 * z2) * ti * ti + PI4 * (1 - 2 * z2) * ti * ti * ti * ti
            term *= exp(-ti * ti * w)
            tom += term
            j++
        }
        sum += tom * C2 / (n * 36.0 * z * z6)
        term = 1.0
        tom = 0.0
        j = 1
        while (j <= JMAX && term > EPS * tom) {
            ti = j.toDouble()
            term = PI2 * ti * ti * exp(-ti * ti * w)
            tom += term
            j++
        }
        sum -= tom * C2 / (n * 18.0 * z * z2)
        term = 1.0
        tom = 0.0
        j = 0
        while (j <= JMAX && abs(term) > EPS * abs(tom)) {
            ti = j + 0.5
            ti = ti * ti
            term =
                -30 * z6 - 90 * z6 * z2 + PI2 * (135 * z4 - 96 * z6) * ti + PI4 * (212 * z4 - 60 * z2) * ti * ti + PI2 * PI4 * ti * ti * ti * (5 -
                        30 * z2)
            term *= exp(-ti * w)
            tom += term
            j++
        }
        sum += tom * C2 / (RACN * n * 3240.0 * z4 * z6)
        term = 1.0
        tom = 0.0
        j = 1
        while (j <= JMAX && abs(term) > EPS * abs(tom)) {
            ti = (j * j).toDouble()
            term = (3 * PI2 * ti * z2 - PI4 * ti * ti) * exp(-ti * w)
            tom += term
            j++
        }
        sum += tom * C2 / (RACN * n * 108.0 * z6)
        return sum
    }

    private fun CalcFloorCeil(
        n: Int,  // sample size
        t: Double,  // = nx
        A: DoubleArray,  // A_i
        Atflo: DoubleArray,  // floor (A_i - t)
        Atcei: DoubleArray // ceiling (A_i + t)
    ) {
        // Precompute A_i, floors, and ceilings for limits of sums in the
        // Pomeranz algorithm
        var i: Int
        val ell = t.toInt() // floor (t)
        var z = t - ell // t - floor (t)
        val w = ceil(t) - t
        if (z > 0.5) {
            i = 2
            while (i <= 2 * n + 2) {
                Atflo[i] = (i / 2 - 2 - ell).toDouble()
                i += 2
            }
            i = 1
            while (i <= 2 * n + 2) {
                Atflo[i] = (i / 2 - 1 - ell).toDouble()
                i += 2
            }
            i = 2
            while (i <= 2 * n + 2) {
                Atcei[i] = (i / 2 + ell).toDouble()
                i += 2
            }
            i = 1
            while (i <= 2 * n + 2) {
                Atcei[i] = (i / 2 + 1 + ell).toDouble()
                i += 2
            }
        } else if (z > 0.0) {
            i = 1
            while (i <= 2 * n + 2) {
                Atflo[i] = (i / 2 - 1 - ell).toDouble()
                i++
            }
            i = 2
            while (i <= 2 * n + 2) {
                Atcei[i] = (i / 2 + ell).toDouble()
                i++
            }
            Atcei[1] = (1 + ell).toDouble()
        } else {                       // z == 0
            i = 2
            while (i <= 2 * n + 2) {
                Atflo[i] = (i / 2 - 1 - ell).toDouble()
                i += 2
            }
            i = 1
            while (i <= 2 * n + 2) {
                Atflo[i] = (i / 2 - ell).toDouble()
                i += 2
            }
            i = 2
            while (i <= 2 * n + 2) {
                Atcei[i] = (i / 2 - 1 + ell).toDouble()
                i += 2
            }
            i = 1
            while (i <= 2 * n + 2) {
                Atcei[i] = (i / 2 + ell).toDouble()
                i += 2
            }
        }
        if (w < z) z = w
        A[1] = 0.0
        A[0] = A[1]
        A[2] = z
        A[3] = 1 - A[2]
        i = 4
        while (i <= 2 * n + 1) {
            A[i] = A[i - 2] + 1
            i++
        }
        A[2 * n + 2] = n.toDouble()
    }

    private fun Pomeranz(n: Int, x: Double): Double {
        // The Pomeranz algorithm to compute the KS distribution
        val EPS = 1.0e-15
        val ENO = 350
        val RENO = Math.scalb(1.0, ENO) // for renormalization of V
        var coreno: Int // counter: how many renormalizations
        val t = n * x
        var w: Double
        var sum: Double
        var minsum: Double
        var i: Int
        var j: Int
        var k: Int
        var s: Int
        var r1: Int
        var r2: Int // Indices i and i-1 for V[i][]
        var jlow: Int
        var jup: Int
        var klow: Int
        var kup: Int
        var kup0: Int
        val A = DoubleArray(2 * n + 3)
        val Atflo = DoubleArray(2 * n + 3)
        val Atcei = DoubleArray(2 * n + 3)
        val V = Array(2) { DoubleArray(n + 2) }
        val H = Array(4) { DoubleArray(n + 2) } // = pow(w, j) / Factorial(j)
        CalcFloorCeil(n, t, A, Atflo, Atcei)
        j = 1
        while (j <= n + 1) {
            V[0][j] = 0.0
            j++
        }
        j = 2
        while (j <= n + 1) {
            V[1][j] = 0.0
            j++
        }
        V[1][1] = RENO
        coreno = 1

        // Precompute H[][] = (A[j] - A[j-1]^k / k!
        H[0][0] = 1.0
        w = 2.0 * A[2] / n
        j = 1
        while (j <= n + 1) {
            H[0][j] = w * H[0][j - 1] / j
            j++
        }
        H[1][0] = 1.0
        w = (1.0 - 2.0 * A[2]) / n
        j = 1
        while (j <= n + 1) {
            H[1][j] = w * H[1][j - 1] / j
            j++
        }
        H[2][0] = 1.0
        w = A[2] / n
        j = 1
        while (j <= n + 1) {
            H[2][j] = w * H[2][j - 1] / j
            j++
        }
        H[3][0] = 1.0
        j = 1
        while (j <= n + 1) {
            H[3][j] = 0.0
            j++
        }
        r1 = 0
        r2 = 1
        i = 2
        while (i <= 2 * n + 2) {
            jlow = (2 + Atflo[i]).toInt()
            if (jlow < 1) jlow = 1
            jup = Atcei[i].toInt()
            if (jup > n + 1) jup = n + 1
            klow = (2 + Atflo[i - 1]).toInt()
            if (klow < 1) klow = 1
            kup0 = Atcei[i - 1].toInt()

            // Find to which case it corresponds
            w = (A[i] - A[i - 1]) / n
            s = -1
            j = 0
            while (j < 4) {
                if (abs(w - H[j][1]) <= EPS) {
                    s = j
                    break
                }
                j++
            }
            minsum = RENO
            r1 = r1 + 1 and 1 // i - 1
            r2 = r2 + 1 and 1 // i
            j = jlow
            while (j <= jup) {
                kup = kup0
                if (kup > j) kup = j
                sum = 0.0
                k = kup
                while (k >= klow) {
                    sum += V[r1][k] * H[s][j - k]
                    k--
                }
                V[r2][j] = sum
                if (sum < minsum) minsum = sum
                j++
            }
            if (minsum < 1.0e-280) {
                // V is too small: renormalize to avoid underflow of probabilities
                j = jlow
                while (j <= jup) {
                    V[r2][j] *= RENO
                    j++
                }
                coreno++ // keep track of log of RENO
            }
            i++
        }
        sum = V[r2][n + 1]
        w = getLogFactorial(n) - coreno * ENO * num_Ln2 + ln(sum)
        return if (w >= 0.0) 1.0 else exp(w)
    }

    private fun cdfSpecial(n: Int, x: Double): Double {
        // The KS distribution is known exactly for these cases

        // For nx^2 > 18, fbar(n, x) is smaller than 5e-16
        if (n * x * x >= 18.0 || x >= 1.0) return 1.0
        if (x <= 0.5 / n) return 0.0
        if (n == 1) return 2.0 * x - 1.0
        if (x <= 1.0 / n) {
            val t = 2.0 * x - 1.0 / n
            val w: Double
            if (n <= NFACT) {
                w = Factorial[n]
                return w * t.pow(n.toDouble())
            }
            w = getLogFactorial(n) + n * ln(t)
            return exp(w)
        }
        return if (x >= 1.0 - 1.0 / n) {
            1.0 - 2.0 * (1.0 - x).pow(n.toDouble())
        } else -1.0
    }

    /**
     * Computes the cumulative probability P[D_n <= x] of the
     * Kolmogorov-Smirnov distribution with sample size n at x.
     * It returns at least 13 decimal degits of precision for n <= 140,
     * at least 5 decimal degits of precision for 140 < n <= 100000,
     * and a few correct decimal digits for n > 100000.
     *
     * @param n sample size
     * @param x value of Kolmogorov-Smirnov statistic
     * @return cumulative probability
     */
    fun cdf(n: Int, x: Double): Double {
        val u = cdfSpecial(n, x)
        if (u >= 0.0) return u
        val w = n * x * x
        if (n <= NEXACT) {
            if (w < 0.754693) return DurbinMatrix(n, x)
            return if (w < 4.0) Pomeranz(n, x) else 1.0 - complementaryCDF(n, x)
        }

        // if (n * x * sqrt(x) <= 1.4)
        return if (w * x * n <= 2.0 && n <= NKOLMO) DurbinMatrix(n, x) else Pelz(n, x)
    }

    private fun fbarSpecial(n: Int, x: Double): Double {
        val w = n * x * x
        if (w >= 370.0 || x >= 1.0) return 0.0
        if (w <= 0.0274 || x <= 0.5 / n) return 1.0
        if (n == 1) return 2.0 - 2.0 * x
        if (x <= 1.0 / n) {
            val v: Double
            val t = 2.0 * x - 1.0 / n
            if (n <= NFACT) {
                v = Factorial[n]
                return 1.0 - v * t.pow(n.toDouble())
            }
            v = getLogFactorial(n) + n * ln(t)
            return 1.0 - exp(v)
        }
        return if (x >= 1.0 - 1.0 / n) {
            2.0 * (1.0 - x).pow(n.toDouble())
        } else -1.0
    }

    /** Computes the complementary cumulative probability P[D_n >= x] of the
     * Kolmogorov-Smirnov distribution with sample size n at x.
     * It returns at least 10 decimal digits of precision for n <= 140,
     * at least 5 decimal digits of precision for 140 < n <= 200000,
     * and a few correct decimal digits for n > 200000.
     *
     * @param n sample size
     * @param x value of Kolmogorov-Smirnov statistic
     * @return complementary cumulative probability
     */
    fun complementaryCDF(n: Int, x: Double): Double {
        val v = fbarSpecial(n, x)
        if (v >= 0.0) return v
        val w = n * x * x
        if (n <= NEXACT) {
            return if (w < 4.0) 1.0 - cdf(n, x) else 2.0 * KSPlusbarUpper(n, x)
        }
        return if (w >= 2.2) 2.0 * KSPlusbarUpper(n, x) else 1.0 - cdf(n, x)
    }

    /*=========================================================================

   The following implements the Durbin matrix algorithm and was programmed
   by G. Marsaglia, Wai Wan Tsang and Jingbo Wong in C.

   I have translated their program in Java. I have made small modifications
   in their program. (Richard Simard)

   =========================================================================*/

    /*
    The C program to compute Kolmogorov's distribution

                K(n,d) = Prob(D_n < d),         where

         D_n = max(x_1-0/n,x_2-1/n...,x_n-(n-1)/n,1/n-x_1,2/n-x_2,...,n/n-x_n)

       with  x_1<x_2,...<x_n  a purported set of n independent uniform [0,1)
       random variables sorted into increasing order.
       See G. Marsaglia, Wai Wan Tsang and Jingbo Wong,
          J.Stat.Software, 8, 18, pp 1--4, (2003).
   */

    //=========================================================================


    /*=========================================================================

   The following implements the Durbin matrix algorithm and was programmed
   by G. Marsaglia, Wai Wan Tsang and Jingbo Wong in C.

   I have translated their program in Java. I have made small modifications
   in their program. (Richard Simard)

   =========================================================================*/
    /*
    The C program to compute Kolmogorov's distribution

                K(n,d) = Prob(D_n < d),         where

         D_n = max(x_1-0/n,x_2-1/n...,x_n-(n-1)/n,1/n-x_1,2/n-x_2,...,n/n-x_n)

       with  x_1<x_2,...<x_n  a purported set of n independent uniform [0,1)
       random variables sorted into increasing order.
       See G. Marsaglia, Wai Wan Tsang and Jingbo Wong,
          J.Stat.Software, 8, 18, pp 1--4, (2003).
   */
    //=========================================================================
    private fun DurbinMatrix(n: Int, d: Double): Double {
        val k: Int
        val m: Int
        var i: Int
        var j: Int
        var g: Int
        val eH: Int
        val h: Double
        var s: Double
        val H: DoubleArray
        val Q: DoubleArray
        val pQ: IntArray

        // Omit next two lines if you require >7 digit accuracy in the right tail
        if (false) {
            s = d * d * n
            if (s > 7.24 || s > 3.76 && n > 99) return 1 - 2 * exp(-(2.000071 + .331 / sqrt(n.toDouble()) + 1.409 / n) * s)
        }
        k = (n * d).toInt() + 1
        m = 2 * k - 1
        h = k - n * d
        H = DoubleArray(m * m)
        Q = DoubleArray(m * m)
        pQ = IntArray(1)
        i = 0
        while (i < m) {
            j = 0
            while (j < m) {
                if (i - j + 1 < 0) H[i * m + j] = 0.0 else H[i * m + j] = 1.0
                j++
            }
            i++
        }
        i = 0
        while (i < m) {
            H[i * m] -= h.pow((i + 1).toDouble())
            H[(m - 1) * m + i] -= h.pow((m - i).toDouble())
            i++
        }
        val tmp1 = 2.0 * h - 1.0
        val tmp2 = tmp1.pow(m.toDouble())
        val tmp = if (tmp1 > 0.0) tmp2 else 0.0
        H[(m - 1) * m] = H[(m - 1) * m] + tmp
        i = 0
        while (i < m) {
            j = 0
            while (j < m) {
                if (i - j + 1 > 0) {
                    g = 1
                    while (g <= i - j + 1) {
                        H[i * m + j] /= g.toDouble()
                        g++
                    }
                }
                j++
            }
            i++
        }
        eH = 0
        mPower(H, eH, Q, pQ, m, n)
        s = Q[(k - 1) * m + k - 1]
        i = 1
        while (i <= n) {
            s = s * i.toDouble() / n
            if (s < INORM) {
                s *= NORM
                pQ[0] -= LOGNORM
            }
            i++
        }
        s *= 10.0.pow(pQ[0].toDouble())
        return s
    }

    private fun mMultiply(A: DoubleArray, B: DoubleArray, C: DoubleArray, m: Int) {
        var i: Int
        var j: Int
        var k: Int
        var s: Double
        i = 0
        while (i < m) {
            j = 0
            while (j < m) {
                s = 0.0
                k = 0
                while (k < m) {
                    s += A[i * m + k] * B[k * m + j]
                    k++
                }
                C[i * m + j] = s
                j++
            }
            i++
        }
    }


    private fun renormalize(V: DoubleArray, m: Int, p: IntArray) {
        for (i in 0 until m * m) V[i] *= INORM
        p[0] += LOGNORM
    }

    private fun mPower(
        A: DoubleArray, eA: Int, V: DoubleArray, eV: IntArray, m: Int,
        n: Int
    ) {
        var i: Int
        if (n == 1) {
            i = 0
            while (i < m * m) {
                V[i] = A[i]
                i++
            }
            eV[0] = eA
            return
        }
        mPower(A, eA, V, eV, m, n / 2)
        val B = DoubleArray(m * m)
        val pB = IntArray(1)
        mMultiply(V, V, B, m)
        pB[0] = 2 * eV[0]
        if (B[m / 2 * m + m / 2] > NORM) renormalize(B, m, pB)
        if (n % 2 == 0) {
            i = 0
            while (i < m * m) {
                V[i] = B[i]
                i++
            }
            eV[0] = pB[0]
        } else {
            mMultiply(A, B, V, m)
            eV[0] = eA + pB[0]
        }
        if (V[m / 2 * m + m / 2] > NORM) renormalize(V, m, eV)
    }
}

fun main() {
    var x: Double
    var y: Double
    var z: Double
    val K = 100
    val n = 60
    System.out.printf("n = %d%n%n", n)
    println("     x                    cdf                        fbar")
    for (j in 0..K) {
        x = j.toDouble() / K
        y = KolmogorovSmirnovDist.cdf(n, x)
        z = KolmogorovSmirnovDist.complementaryCDF(n, x)
        System.out.printf("%8.3f     %22.15g      %22.15g%n", x, y, z)
    }
}