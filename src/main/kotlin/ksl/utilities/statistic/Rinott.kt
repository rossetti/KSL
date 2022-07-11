package ksl.utilities.statistic

import ksl.utilities.Interval
import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.Normal
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.math.FunctionIfc
import ksl.utilities.rootfinding.BisectionRootFinder
import ksl.utilities.rootfinding.RootFinder

/**
 * Functions used to calculate Rinott constants
 *
 * Derived from Fortran code in
 *
 * Design and Analysis of Experiments for Statistical Selection,
 * Screening, and Multiple Comparisons
 *
 * Robert E. Bechhofer, Thomas J. Santner, David M. Goldsman
 *
 * ISBN: 978-0-471-57427-9
 * Wiley, 1995
 *
 * Original Fortran code available on the authors' website
 * http://www.stat.osu.edu/~tjs/REB-TJS-DMG/describe.html
 *
 * Revised, May 5, 2022, M. D. Rossetti, rossetti@uark.edu
 */
class Rinott {
    private val X = doubleArrayOf(
        .44489365833267018419E-1,
        .23452610951961853745,
        .57688462930188642649,
        .10724487538178176330E1,
        .17224087764446454411E1,
        .25283367064257948811E1,
        .34922132730219944896E1,
        .46164567697497673878E1,
        .59039585041742439466E1,
        .73581267331862411132E1,
        .89829409242125961034E1,
        .10783018632539972068E2,
        .12763697986742725115E2,
        .14931139755522557320E2,
        .17292454336715314789E2,
        .19855860940336054740E2,
        .22630889013196774489E2,
        .25628636022459247767E2,
        .28862101816323474744E2,
        .32346629153964737003E2,
        .36100494805751973804E2,
        .40145719771539441536E2,
        .44509207995754937976E2,
        .49224394987308639177E2,
        .54333721333396907333E2,
        .59892509162134018196E2,
        .65975377287935052797E2,
        .72687628090662708639E2,
        .80187446977913523067E2,
        .88735340417892398689E2,
        .98829542868283972559E2,
        .11175139809793769521E3
    )
    private val LNGAM = DoubleArray(50)
    private val WEX = DoubleArray(32)

    private val myInterval = Interval(0.0, 20.0)
    private val myRootFinder: RootFinder = BisectionRootFinder(SearchFunction(), myInterval)
    private var pStar = 0.975
    private var dof = 50
    private var numTreatments = 10

    init {
        setupWEXArray()
        setupLogGammaArray()
        myRootFinder.maximumIterations = 200
    }

    private fun setupLogGammaArray() {
        for (i in LNGAM.indices) {
            LNGAM[i] = Gamma.logGammaFunction((i + 1.0) / 2.0)
        }
    }

    private fun setupWEXArray() {
        val W = doubleArrayOf(
            .10921834195238497114,
            .21044310793881323294,
            .23521322966984800539,
            .19590333597288104341,
            .12998378628607176061,
            .70578623865717441560E-1,
            .31760912509175070306E-1,
            .11918214834838557057E-1,
            .37388162946115247897E-2,
            .98080330661495513223E-3,
            .21486491880136418802E-3,
            .39203419679879472043E-4,
            .59345416128686328784E-5,
            .74164045786675522191E-6,
            .76045678791207814811E-7,
            .63506022266258067424E-8,
            .42813829710409288788E-9,
            .23058994918913360793E-10,
            .97993792887270940633E-12,
            .32378016577292664623E-13,
            .81718234434207194332E-15,
            .15421338333938233722E-16,
            .21197922901636186120E-18,
            .20544296737880454267E-20,
            .13469825866373951558E-22,
            .56612941303973593711E-25,
            .14185605454630369059E-27,
            .19133754944542243094E-30,
            .11922487600982223565E-33,
            .26715112192401369860E-37,
            .13386169421062562827E-41,
            .45105361938989742322E-47
        )
        for (i in 1..32) {
            WEX[i - 1] = W[i - 1] * Math.exp(X[i - 1])
        }
    }

    private inner class SearchFunction : FunctionIfc {
        override fun f(x: Double): Double {
            return rinottIntegral(x) - pStar
        }
    }

    /**
     * Calculates Rinott constants as per Table 2.13 of Bechhofer et al.
     *
     * Derived from Fortran code in
     *
     * Design and Analysis of Experiments for Statistical Selection,
     * Screening, and Multiple Comparisons
     *
     * Robert E. Bechhofer, Thomas J. Santner, David M. Goldsman
     *
     * @param numTreatments the number of treatments in the comparison, must be at least 2
     * @param pStar the lower bound on probably of correct selection
     * @param dof the number of degrees of freedom.  If the first stage samples size is n_0, then
     * the dof = n_0 - 1, must be 4 or more
     * @return the computed Rinott constant
     */
    fun rinottConstant(numTreatments: Int, pStar: Double, dof: Int): Double {
        numTreatments(numTreatments)
        degreesOfFreedom(dof)
        pStar(pStar)
        myRootFinder.setUpSearch(SearchFunction(), myInterval, 4.0)
        myRootFinder.evaluate()
        if (!myRootFinder.hasConverged()) {
            KSL.logger.warn("The Rinott constant calculation did not converge")
            return Double.NaN
        }
        return myRootFinder.result
    }

    /**
     *
     * @param p the minimum desired probability of correct selection
     */
    fun pStar(p: Double) {
        require((0.0 < p) && (p < 1.0)) { "probability must be in (0,1)" }
        pStar = p
    }

    /**
     *
     * @param dof the degrees of freedom, must be greater than or equal to 4
     */
    fun degreesOfFreedom(dof: Int) {
        require(dof >= 4) { "Degrees of freedom in Rinott must be >=5" }
        this.dof = dof
    }

    /**
     *
     * @param n the number of treatments to compare, must be greater than or equal to 2
     */
    fun numTreatments(n: Int) {
        require(n > 1) { "Number of treatments in Rinott must be >=2" }
        numTreatments = n
    }

    /**
     *
     * @param x the value for evaluation
     * @return the value of the rinott integral. See page 61 of Bechhofer et al
     */
    fun rinottIntegral(x: Double): Double {
        var ans = 0.0
        for (j in 1..WEX.size) {
            var tmp = 0.0
            for (i in 1..WEX.size) {
                val z = x / Math.sqrt(dof * (1.0 / X[i - 1] + 1.0 / X[j - 1]))
                val zcdf = Normal.stdNormalCDF(z)
                val chi2pdf = chiSquaredPDF(dof, X[i - 1])
                tmp = tmp + WEX[i - 1] * zcdf * chi2pdf
            }
            tmp = Math.pow(tmp, (numTreatments - 1).toDouble())
            ans = ans + WEX[j - 1] * tmp * chiSquaredPDF(dof, X[j - 1])
        }
        return ans
    }

    /**
     * Chi-distribution PDF
     *
     * @param dof   Degree of freedom
     * @param x     The point of evaluation
     * @return The PDF of the Chi^2 distribution with dof of freedom
     */
    private fun chiSquaredPDF(dof: Int, x: Double): Double {
        val dof2 = dof.toDouble() / 2.0
        val lng = if (dof > LNGAM.size) {
            Gamma.logGammaFunction(dof2)
        } else {
            LNGAM[dof - 1]
        }
        val tmp = -dof2 * Math.log(2.0) - lng + (dof2 - 1.0) * Math.log(x) - x / 2.0
        return Math.exp(tmp)
    }

}

fun main(){
    println("Running rinott")
    val ans = doubleArrayOf(4.045, 6.057, 6.893, 5.488, 6.878, 8.276, 8.352)
    val p = doubleArrayOf(.975, .975, .975, .9, .95, 0.975, 0.975)
    val n = intArrayOf(10, 1000, 10000, 1000, 20000, 800000, 1000000)
//    for (i in n.indices) {
//        val result: Double = MultipleComparisonAnalyzer.rinottConstant(n[i], p[i], 50)
//        System.out.printf("ans[%d] = %f, result = %f %n", i, ans[i], result)
//        assert(result > ans[i] - 0.01 && result < ans[i] + 0.3)
//    }

    println()

    println()
    val r = Rinott()
    for (i in n.indices) {
        val result = r.rinottConstant(n[i], p[i], 50)
        System.out.printf("ans[%d] = %f, result = %f %n", i, ans[i], result)
        assert(result > ans[i] - 0.01 && result < ans[i] + 0.3)
    }

    val nu = intArrayOf(5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 30, 40, 50)
    val t = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10)
    val rc = Array(nu.size) { DoubleArray(t.size) }
    for (j in t.indices) {
        for (i in nu.indices) {
            rc[i][j] = r.rinottConstant(t[j], 0.9, nu[i] - 1)
        }
    }
    println()
    println()

    KSLFileUtil.write(rc, KSLFileUtil.SOUT)
}