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

import ksl.utilities.io.KSL
import kotlin.math.*

/*
 * Copyright (c) 2022. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

/*
 *  Mathlib : A C Library of Special Functions
 *  Copyright (C) 1998   Ross Ihaka
 *  Copyright (C) 2000-9 The R Development Core Team
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, a copy is available at
 *  http://www.r-project.org/Licenses/
 */

/**
 * Computes the probability and quantile that the studentized
 * range, each based on n means and with df degrees of freedom
 *
 * See functions: qtukey() and ptukey() from statistical software: R
 *
 * The algorithm is based on that of the reference.
 *
 * REFERENCE
 *
 * Copenhaver, Margaret Diponzio & Holland, Burt S.
 * Multiple comparisons of simple effects in
 * the two-way analysis of variance with fixed effects.
 * Journal of Statistical Computation and Simulation,
 * Vol.30, pp.1-15, 1988.
 *
 */
object Tukey {

    private var qTukeyEPS = 0.0001
    private var qTukeyMaxIterations = 50

    /**
     * Sets the precision for the computation of the invCDF
     * Default is 0.0001
     * @param eps the desired precision
     */
    fun setQTukeyPrecision(eps: Double) {
        if (eps <= 0.0) {
            qTukeyEPS = 0.0001
        }
        qTukeyEPS = eps
    }

    /**
     * Sets the maximum number of iterations for the computation of invCDF
     * default is 50
     *
     * @param iterations the number of iterations
     */
    fun setQTukeyMaxIterations(iterations: Int) {
        qTukeyMaxIterations = 50.coerceAtLeast(iterations)
    }

    /**
     * @param p      the probability, typically a confidence level (1-alpha), must be in (0,1)
     * @param nMeans the number of columns or treatments (means), must be greater than or equal to 2.0
     * @param df     the degrees of freedom, must be greater than or equal to 1.0
     * @return the quantile of the Tukey distribution
     */
    fun invCDF(p: Double, nMeans: Double, df: Double): Double {
        require((0.0 < p) && (p < 1.0)) { "Supplied probability was $p Probability must be (0,1)" }
        require(nMeans >= 2.0) { "The number of groups must be >= 2" }
        require(df >= 1.0) { "The degrees of freedom must be >= 1" }
        return qtukey(p, nMeans, df, 1.0, true, false)
    }

    /**
     * @param q      value of studentized range, must be greater than or equal to 0.0
     * @param nMeans the number of columns or treatments (means), must be greater than or equal to 2.0
     * @param df     the degrees of freedom, must be greater than or equal to 1.0
     * @return the probability integral over [0, q]
     */
    fun cdf(q: Double, nMeans: Double, df: Double): Double {
        require(nMeans >= 2.0) { "The number of groups must be >= 2" }
        require(df >= 1.0) { "The degrees of freedom must be >= 1" }
        if (q <= 0.0){
            return 0.0
        }
        return ptukey(q, nMeans, df, 1.0, true, false)
    }

    /*  wprob() :

    This function calculates probability integral of Hartley's
    form of the range.

    w     = value of range
    rr    = no. of rows or groups
    cc    = no. of columns or treatments
    ir    = error flag = 1 if pr_w probability > 1
    pr_w = returned probability integral from (0, w)

    program will not terminate if ir is raised.

    bb = upper limit of legendre integration
    iMax = maximum acceptable value of integral
    nleg = order of legendre quadrature
    ihalf = int ((nleg + 1) / 2)
    wlar = value of range above which wincr1 intervals are used to
           calculate second part of integral,
           else wincr2 intervals are used.
    C1, C2, C3 = values which are used as cutoffs for terminating
    or modifying a calculation.

    M_1_SQRT_2PI = 1 / sqrt(2 * pi);  from abramowitz & stegun, p. 3.
    M_SQRT2 = sqrt(2)
    xleg = legendre 12-point nodes
    aleg = legendre 12-point coefficients
     */
    /**
     * This function calculates probability integral of Hartley's form of the range.
     *
     * @param w  the value of range
     * @param rr the number of ranges, always 1.0
     * @param cc the number of columns or treatments
     * @return the value of the probability integral
     */
    private fun wprob(w: Double, rr: Double, cc: Double): Double {
        require(!w.isNaN()) { "The supplied w value was Double.NaN in Tukey wprob()" }
        val nleg = 12
        val ihalf = 6
        val M_1_SQRT_2PI = 0.398942280401432677939946059934
        /* const double iMax  = 1.; not used if = 1*/
        val C1 = -30.0
        val C2 = -50.0
        val C3 = 60.0
        val bb = 8.0
        val wlar = 3.0
        val wincr1 = 2.0
        val wincr2 = 3.0
        val xleg = doubleArrayOf(
            0.981560634246719250690549090149,
            0.904117256370474856678465866119,
            0.769902674194304687036893833213,
            0.587317954286617447296702418941,
            0.367831498998180193752691536644,
            0.125233408511468915472441369464
        )
        val aleg = doubleArrayOf(
            0.047175336386511827194615961485,
            0.106939325995318430960254718194,
            0.160078328543346226334652529543,
            0.203167426723065921749064455810,
            0.233492536538354808760849898925,
            0.249147045813402785000562436043
        )
        var a: Double
        var ac: Double
        var pr_w: Double
        var b: Double
        val binc: Double
        var blb: Double
        var c: Double
        val cc1: Double
        var pminus: Double
        var pplus: Double
        var qexpo: Double
        val qsqz: Double
        var rinsum: Double
        var wi: Double
        val wincr: Double
        var xx: Double
        var bub: Double
        var einsum: Double
        var elsum: Double
        var j: Int
        var jj: Int
        qsqz = w * 0.5

        /* if w >= 16 then the integral lower bound (occurs for c=20) */
        /* is 0.99999999999995 so return a value of 1. */
        if (qsqz >= bb) return 1.0

        /* find (f(w/2) - 1) ^ cc */
        /* (first term in integral of hartley's form). */
        pr_w = 2.0 * Normal.stdNormalCDF(qsqz) - 1.0
        /* if pr_w ^ cc < 2e-22 then set pr_w = 0 */
        pr_w = if (pr_w >= Math.exp(C2 / cc)) Math.pow(pr_w, cc) else 0.0

        /* if w is large then the second component of the */
        /* integral is small, so fewer intervals are needed. */
        wincr = if (w > wlar) wincr1 else wincr2

        /* find the integral of second term of hartley's form */
        /* for the integral of the range for equal-length */
        /* intervals using legendre quadrature.  limits of */
        /* integration are from (w/2, 8).  two or three */
        /* equal-length intervals are used. */

        /* blb and bub are lower and upper limits of integration. */
        blb = qsqz
        binc = (bb - qsqz) / wincr
        bub = blb + binc
        einsum = 0.0

        /* integrate over each interval */cc1 = cc - 1.0
        wi = 1.0
        while (wi <= wincr) {
            elsum = 0.0
            a = 0.5 * (bub + blb)

            /* legendre quadrature with order = nleg */b = 0.5 * (bub - blb)
            jj = 1
            while (jj <= nleg) {
                if (ihalf < jj) {
                    j = nleg - jj + 1
                    xx = xleg[j - 1]
                } else {
                    j = jj
                    xx = -xleg[j - 1]
                }
                c = b * xx
                ac = a + c

                /* if exp(-qexpo/2) < 9e-14, */
                /* then doesn't contribute to integral */qexpo = ac * ac
                if (qexpo > C3) break
                pplus = 2.0 * Normal.stdNormalCDF(ac)
                pminus = 2.0 * Normal.stdNormalCDF(ac - w)
                /* if rinsum ^ (cc-1) < 9e-14, */
                /* then doesn't contribute to integral */rinsum = pplus * 0.5 - pminus * 0.5
                if (rinsum >= Math.exp(C1 / cc1)) {
                    rinsum = aleg[j - 1] * Math.exp(-(0.5 * qexpo)) * Math.pow(rinsum, cc1)
                    elsum += rinsum
                }
                jj++
            }
            elsum *= 2.0 * b * cc * M_1_SQRT_2PI
            einsum += elsum
            blb = bub
            bub += binc
            wi++
        }

        /* if pr_w ^ rr < 9e-14, then return 0 */pr_w = einsum + pr_w
        if (pr_w <= Math.exp(C1 / rr)) return 0.0
        pr_w = Math.pow(pr_w, rr)
        return if (pr_w >= 1.0) 1.0 else pr_w
    }

    /*  function ptukey() [was qprob() ]:

    q = value of studentized range
    rr = no. of rows or groups
    cc = no. of columns or treatments
    df = degrees of freedom of error term
    ir[0] = error flag = 1 if wprob probability > 1
    ir[1] = error flag = 1 if qprob probability > 1

    qprob = returned probability integral over [0, q]

    The program will not terminate if ir[0] or ir[1] are raised.

    All references in wprob to Abramowitz and Stegun
    are from the following reference:

    Abramowitz, Milton and Stegun, Irene A.
    Handbook of Mathematical Functions.
    New York:  Dover publications, Inc. (1970).

    All constants taken from this text are
    given to 25 significant digits.

    nlegq = order of legendre quadrature
    ihalfq = int ((nlegq + 1) / 2)
    eps = max. allowable value of integral
    eps1 & eps2 = values below which there is
              no contribution to integral.

    d.f. <= dhaf:	integral is divided into ulen1 length intervals.  else
    d.f. <= dquar:	integral is divided into ulen2 length intervals.  else
    d.f. <= deigh:	integral is divided into ulen3 length intervals.  else
    d.f. <= dlarg:	integral is divided into ulen4 length intervals.

    d.f. > dlarg:	the range is used to calculate integral.

    M_LN2 = log(2)

    xlegq = legendre 16-point nodes
    alegq = legendre 16-point coefficients

    The coefficients and nodes for the legendre quadrature used in
    qprob and wprob were calculated using the algorithms found in:

    Stroud, A. H. and Secrest, D.
    Gaussian Quadrature Formulas.
    Englewood Cliffs,
    New Jersey:  Prentice-Hall, Inc, 1966.

    All values matched the tables (provided in same reference)
    to 30 significant digits.

    f(x) = .5 + erf(x / sqrt(2)) / 2      for x > 0

    f(x) = erfc( -x / sqrt(2)) / 2	      for x < 0

    where f(x) is standard normal c. d. f.

    if degrees of freedom large, approximate integral
    with range distribution.
     */
    private fun ptukey(
        q: Double,
        nMeans: Double,
        df: Double,
        nRanges: Double,
        lower_tail: Boolean,
        log_p: Boolean
    ): Double {
        if (q.isInfinite() || nRanges.isInfinite() || nMeans.isInfinite() || df.isInfinite()) return Double.NaN

        require(!q.isNaN()) { "q was Double.NaN in ptukey()" }
        val nlegq = 16
        val ihalfq = 8
        val M_LN2 = 0.693147180559945309417232121458
        /*  const double eps = 1.0; not used if = 1 */
        val eps1 = -30.0
        val eps2 = 1.0e-14
        val dhaf = 100.0
        val dquar = 800.0
        val deigh = 5000.0
        val dlarg = 25000.0
        val ulen1 = 1.0
        val ulen2 = 0.5
        val ulen3 = 0.25
        val ulen4 = 0.125
        val xlegq = doubleArrayOf(
            0.989400934991649932596154173450,
            0.944575023073232576077988415535,
            0.865631202387831743880467897712,
            0.755404408355003033895101194847,
            0.617876244402643748446671764049,
            0.458016777657227386342419442984,
            0.281603550779258913230460501460,
            0.950125098376374401853193354250e-1
        )
        val alegq = doubleArrayOf(
            0.271524594117540948517805724560e-1,
            0.622535239386478928628438369944e-1,
            0.951585116824927848099251076022e-1,
            0.124628971255533872052476282192,
            0.149595988816576732081501730547,
            0.169156519395002538189312079030,
            0.182603415044923588866763667969,
            0.189450610455068496285396723208
        )
        var ans: Double
        val f2: Double
        val f21: Double
        var f2lf: Double
        val ff4: Double
        var otsum = 0.0
        var qsqz: Double
        var rotsum: Double
        var t1: Double
        var twa1: Double
        val ulen: Double
        var wprb: Double
        var i: Int
        var j: Int
        var jj: Int

        if (q <= 0) return if (lower_tail) if (log_p) Double.NEGATIVE_INFINITY else 0.0 else if (log_p) 0.0 else 1.0

        /* df must be > 1 */
        /* there must be at least two values */
        if (df < 2 || nRanges < 1 || nMeans < 2) return Double.NaN
        if (java.lang.Double.isInfinite(q)) return if (lower_tail) if (log_p) 0.0 else 1.0 else if (log_p) Double.NEGATIVE_INFINITY else 0.0
        if (df > dlarg) {
            //return R_DT_val(wprob(q, rr, cc));
            val x = wprob(q, nRanges, nMeans)
            return if (lower_tail) if (log_p) Math.log(x) else x else if (log_p) Math.log1p(-x) else 0.5 - x + 0.5
        }

        /* calculate leading constant */
        f2 = df * 0.5
        /* lgammafn(u) = log(gamma(u)) */
//        f2lf = ((f2 * log(df)) - (df * M_LN2)) - lgammafn(f2);
        f2lf = f2 * Math.log(df) - df * M_LN2 - Gamma.logGammaFunction(f2)
        f21 = f2 - 1.0

        /* integral is divided into unit, half-unit, quarter-unit, or */
        /* eighth-unit length intervals depending on the value of the */
        /* degrees of freedom. */
        ff4 = df * 0.25
        ulen = if (df <= dhaf) ulen1 else if (df <= dquar) ulen2 else if (df <= deigh) ulen3 else ulen4
        f2lf += Math.log(ulen)

        /* integrate over each subinterval */
        ans = 0.0
        i = 1
        while (i <= 50) {
            otsum = 0.0

            /* legendre quadrature with order = nlegq */
            /* nodes (stored in xlegq) are symmetric around zero. */
            twa1 = (2 * i - 1) * ulen
            jj = 1
            while (jj <= nlegq) {
                if (ihalfq < jj) {
                    j = jj - ihalfq - 1
                    t1 = f2lf + f21 * Math.log(twa1 + xlegq[j] * ulen) - (xlegq[j] * ulen + twa1) * ff4
                } else {
                    j = jj - 1
                    t1 = f2lf + f21 * Math.log(twa1 - xlegq[j] * ulen) + (xlegq[j] * ulen - twa1) * ff4
                }

                /* if exp(t1) < 9e-14, then doesn't contribute to integral */
                if (t1 >= eps1) {
                    qsqz = if (ihalfq < jj) {
                        val tmp = (xlegq[j] * ulen + twa1) * 0.5
                        require(tmp >= 0.0) { "tmp was negative" }
                        q * sqrt((xlegq[j] * ulen + twa1) * 0.5)
                    } else {
                        val tmp2 = (-(xlegq[j] * ulen) + twa1) * 0.5
                        require(tmp2 >= 0.0) { "tmp2 was negative" }
                        q * sqrt((-(xlegq[j] * ulen) + twa1) * 0.5)
                    }
                    require(!qsqz.isNaN()) { "qsqz became Double.NaN" }
                    /* call wprob to find integral of range portion */
                    wprb = wprob(qsqz, nRanges, nMeans)
                    rotsum = wprb * alegq[j] * Math.exp(t1)
                    otsum += rotsum
                }
                jj++
            }

            /* if integral for interval i < 1e-14, then stop.
             * However, in order to avoid small area under left tail,
             * at least  1 / ulen  intervals are calculated.
             */if (i * ulen >= 1.0 && otsum <= eps2) break

            /* end of interval i */
            /* L330: */ans += otsum
            i++
        }
        if (otsum > eps2) { /* not converged */
            KSL.logger.warn { "The computation for Tukey cdf did not converge due to precision!" }
            return Double.NaN
        }
        if (ans > 1.0) ans = 1.0
        //return R_DT_val(ans);
        return if (lower_tail) if (log_p) Math.log(ans) else ans else if (log_p) Math.log1p(-ans) else 0.5 - ans + 0.5
    }

    /* qinv() :
     *	this function finds percentage point of the studentized range
     *	which is used as initial estimate for the secant method.
     *	function is adapted from portion of algorithm as 70
     *	from applied statistics (1974) ,vol. 23, no. 1
     *	by odeh, r. e. and evans, j. o.
     *
     *	  p = percentage point
     *	  nMeans = no. of columns or treatments (means)
     *	  df = degrees of freedom
     *	  qinv = returned initial estimate
     *
     *	vmax is cutoff above which degrees of freedom
     *	is treated as infinity.
     */
    private fun qinv(p: Double, nMeans: Double, df: Double): Double {
        val p0 = 0.322232421088
        val q0 = 0.993484626060e-01
        val p1 = -1.0
        val q1 = 0.588581570495
        val p2 = -0.342242088547
        val q2 = 0.531103462366
        val p3 = -0.204231210125
        val q3 = 0.103537752850
        val p4 = -0.453642210148e-04
        val q4 = 0.38560700634e-02
        val c1 = 0.8832
        val c2 = 0.2368
        val c3 = 1.214
        val c4 = 1.208
        val c5 = 1.4142
        val vmax = 120.0
        val ps: Double
        var q: Double
        var t: Double
        val yi: Double
        ps = 0.5 - 0.5 * p
        yi = Math.sqrt(Math.log(1.0 / (ps * ps)))
        t = yi + ((((yi * p4 + p3) * yi + p2) * yi + p1) * yi + p0) / (((yi * q4 + q3) * yi + q2) * yi + q1) * yi + q0
        if (df < vmax) t += (t * t * t + t) / df / 4.0
        q = c1 - c2 * t
        if (df < vmax) q += -c3 / df + c4 * t / df
        return t * (q * Math.log(nMeans - 1.0) + c5)
    }

    /*
     *  Copenhaver, Margaret Diponzio & Holland, Burt S.
     *  Multiple comparisons of simple effects in
     *  the two-way analysis of variance with fixed effects.
     *  Journal of Statistical Computation and Simulation,
     *  Vol.30, pp.1-15, 1988.
     *
     *  Uses the secant method to find critical values.
     *
     *  p = confidence level (1 - alpha)
     *  rr = no. of rows or groups
     *  cc = no. of columns or treatments
     *  df = degrees of freedom of error term
     *
     *  ir(1) = error flag = 1 if wprob probability > 1
     *  ir(2) = error flag = 1 if ptukey probability > 1
     *  ir(3) = error flag = 1 if convergence not reached in 50 iterations
     *		       = 2 if df < 2
     *
     *  qtukey = returned critical value
     *
     *  If the difference between successive iterates is less than eps,
     *  the search is terminated
     */
    private fun qtukey(
        p: Double,
        nMeans: Double,
        df: Double,
        nRanges: Double,
        lower_tail: Boolean,
        log_p: Boolean
    ): Double {
        var pp = p
        val eps = qTukeyEPS
        val maxiter = qTukeyMaxIterations
        var ans: Double = 0.0
        var valx0: Double
        var valx1: Double
        var x0: Double
        var x1: Double
        var xabs: Double
        if (pp.isNaN() || nRanges.isNaN() || nMeans.isNaN() || df.isNaN()) {
            //ML_ERROR(ME_DOMAIN, "qtukey");
            return pp + nRanges + nMeans + df
        }

        // df must be > 1 ; there must be at least two values
        if (df < 2 || nRanges < 1 || nMeans < 2) return Double.NaN

        //R_Q_P01_boundaries(p, 0, ML_POSINF);
        if (log_p) {
            if (pp > 0) return Double.NaN
            if (pp == 0.0) return if (lower_tail) Double.POSITIVE_INFINITY else 0.0
            if (pp == Double.NEGATIVE_INFINITY) return if (lower_tail) 0.0 else Double.POSITIVE_INFINITY
        } else { /* !log_p */
            if (pp < 0 || pp > 1) return Double.NaN
            if (pp == 0.0) return if (lower_tail) 0.0 else Double.POSITIVE_INFINITY
            if (pp == 1.0) return if (lower_tail) Double.POSITIVE_INFINITY else 0.0
        }

        //p = R_DT_qIv(p); /* lower_tail,non-log "p"
        pp = if (log_p) if (lower_tail) exp(pp) else -expm1(pp) else if (lower_tail) pp else 0.5 - pp + 0.5

        // Initial value
        x0 = qinv(pp, nMeans, df)

        // Find prob(value < x0)
        valx0 = ptukey(x0, nMeans, df, nRanges, lower_tail = true, log_p = false) - pp

        // Find the second iterate and prob(value < x1).
        // If the first iterate has probability value
        // exceeding p then second iterate is 1 less than
        // first iterate; otherwise it is 1 greater.
        x1 = if (valx0 > 0.0) 0.0.coerceAtLeast(x0 - 1.0) else x0 + 1.0
        valx1 = ptukey(x1, nMeans, df, nRanges, lower_tail = true, log_p = false) - pp

        // Find new iterate
        var iter = 1
        while (iter < maxiter) {
//            require(!x1.isNaN()){"x1 iterated to Double.NaN"}
//            require(!valx1.isNaN()){"valx1 iterated to Double.NaN"}
//            require(!x0.isNaN()){"x0 iterated to Double.NaN"}
            require((valx1 - valx0) != 0.0){"The denominator got to 0.0"}
            ans = x1 - valx1 * (x1 - x0) / (valx1 - valx0)
            // require(!ans.isNaN()){"ans iterated to Double.NaN"}
            valx0 = valx1

            // New iterate must be >= 0
            x0 = x1
            if (ans < 0.0) {
                ans = 0.0
                valx1 = -pp
            }
            // Find prob(value < new iterate)
            valx1 = ptukey(ans, nMeans, df, nRanges, lower_tail = true, log_p = false) - pp
            x1 = ans

            // If the difference between two successive
            // iterates is less than eps, stop
            xabs = abs(x1 - x0)
            if (xabs < eps) return ans
            iter++
        }

        // The process did not converge in 'maxiter' iterations
        //ML_ERROR(ME_NOCONV, "qtukey");
        KSL.logger.warn { "The computation of invCDF did not converge after $maxiter iterations" }
        return Double.NaN
    }

}

fun main() {
    val k = doubleArrayOf(2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
    val p = 0.99
    val df = 5.0
    for (i in k.indices) {
        val result = Tukey.invCDF(p, k[i], df)
        System.out.printf("p = %f \t df = %f \t k =%f \t result = %f %n", p, df, k[i], result)
    }
    println()
    val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
    val nMeans = 2.0
    for (i in k.indices) {
        val result = Tukey.cdf(x[i], nMeans, df)
        System.out.printf("nMeans = %f \t df =%f \t x = %f \t result = %f %n", nMeans, df, x[i], result)
    }

    // matches Table 8.1 of Goldsman and Nelson chapter 8, table 8.1
    val q = Tukey.invCDF(0.95, 4.0, 20.0)
    System.out.printf("p = %f \t df = %f \t k =%f \t result = %f %n", 0.95, 20.0, 4.0, q)
}
