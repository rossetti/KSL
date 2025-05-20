package ksl.utilities.random.rvariable


import ksl.utilities.KSLArrays
import ksl.utilities.addConstant
import ksl.utilities.io.write
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamProviderIfc
import org.hipparchus.linear.CholeskyDecomposition
import org.hipparchus.linear.MatrixUtils
import kotlin.math.sqrt

/**
 * Generations multi-dimensional normal random variates
 * @param means       the desired mean of the random variable, must not be null
 * @param covariances the covariance of the random variable
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class MVNormalRV  @JvmOverloads constructor(
    means: DoubleArray,
    covariances: Array<DoubleArray>,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : MVRVariable(streamNum, streamProvider, name) {

    override val dimension: Int

    private val myCovariances: Array<DoubleArray>
    private val cfL: Array<DoubleArray> // Cholesky decomposition array
    private val myMeans: DoubleArray
    private val normalRV: NormalRV

    init {
        require(isValidCovariance(covariances)) { "The covariance array was not valid" }
        dimension = covariances.size
        cfL = choleskyDecomposition(covariances)
        this.myCovariances = KSLArrays.copy2DArray(covariances)
        this.myMeans = means.copyOf(means.size)
        normalRV = NormalRV(0.0, 1.0, streamNum, streamProvider)
    }

    val means
        get() = myMeans.copyOf()

    val covariances
        get() = KSLArrays.copy2DArray(myCovariances)

    val correlations
        get() = convertToCorrelation(myCovariances)

    val choleskyDecomposition
        get() = KSLArrays.copy2DArray(cfL)


    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The length of the array was not the proper dimension" }
        val c: DoubleArray = KSLArrays.postProduct(cfL, normalRV.sample(dimension))
        val result: DoubleArray = KSLArrays.addElements(myMeans, c)
        System.arraycopy(result, 0, array, 0, result.size)
    }

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): MVNormalRV {
        return MVNormalRV(means, covariances, streamNumber, rnStreamProvider)
    }

    override fun toString(): String {
        val sb = StringBuilder("MVNormalRV")
        sb.appendLine()
        sb.append("nDim = ").append(dimension)
        sb.appendLine()
        sb.append("means = ")
        sb.appendLine()
        sb.append("[")
        sb.append(KSLArrays.toCSVString(myMeans))
        sb.append("]")
        sb.appendLine()
        sb.append("covariances = ")
        sb.appendLine()
        for (i in myCovariances.indices) {
            sb.append("[")
            sb.append(KSLArrays.toCSVString(myCovariances[i]))
            sb.append("]")
            sb.appendLine()
        }
        sb.append("Cholesky decomposition = ")
        sb.appendLine()
        for (i in cfL.indices) {
            sb.append("[")
            sb.append(KSLArrays.toCSVString(cfL[i]))
            sb.append("]")
            sb.appendLine()
        }
        sb.appendLine()
        return sb.toString()
    }

    companion object {

        /** Creates a standard MVN with means 0.0 and variances 1.0, with the
         *  supplied correlation matrix.
         *
         * @param correlation the correlation matrix as an array
         * @param stream      the source for randomness
         * @return the created multi-variate normal
         */
        fun createStandardMVN(
            correlation: Array<DoubleArray>,
            streamNumber: Int = 0,
            streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
            name: String? = null
        ) : MVNormalRV {
            val d = correlation.size
            val means = DoubleArray(d)
            val sigmas = DoubleArray(d)
            sigmas.addConstant(1.0)
            return createRV(means, sigmas, correlation, streamNumber, streamProvider, name)
        }

        /**
         * @param means       the means for the distribution
         * @param stdDevs     an array holding the standard deviations
         * @param correlation the correlation matrix as an array
         * @param stream      the source for randomness
         * @return the created multi-variate normal
         */
        fun createRV(
            means: DoubleArray,
            stdDevs: DoubleArray,
            correlation: Array<DoubleArray>,
            streamNumber: Int = 0,
            streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
            name: String? = null
        ): MVNormalRV {
            val covariances = convertToCovariance(stdDevs, correlation)
            return MVNormalRV(means, covariances, streamNumber, streamProvider, name)
        }

        /**
         * @param stdDevs     an array holding the standard deviations
         * @param correlation the correlation matrix as an array
         * @return the covariance matrix as determined by the correlation and standard deviations
         */
        fun convertToCovariance(stdDevs: DoubleArray, correlation: Array<DoubleArray>): Array<DoubleArray> {
            require(correlation.size == stdDevs.size) { "The correlation array dimension does not match the std deviations length" }
            require(isValidCorrelation(correlation)) { "Not a valid correlation array" }
            require(KSLArrays.isStrictlyPositive(stdDevs)) { "Not a valid std dev array" }
            val s1 = MatrixUtils.createRealDiagonalMatrix(stdDevs)
            val s2 = MatrixUtils.createRealDiagonalMatrix(stdDevs)
            val cor = MatrixUtils.createRealMatrix(correlation)
            val cov = s1.multiply(cor).multiply(s2)
            return cov.data
        }

        /**
         * @param correlation the correlation matrix to check, must not be null
         * @return true if elements are valid correlation values
         */
        fun isValidCorrelation(correlation: Array<DoubleArray>): Boolean {
            if (correlation.size <= 1) {
                return false
            }
            if (!KSLArrays.isSquare(correlation)) {
                return false
            }
            for (i in correlation.indices) {
                for (j in correlation.indices) {
                    if (correlation[i][j] < -1.0 || correlation[i][j] > 1.0) {
                        if (KSLMath.equal(correlation[i][j], 1.0)){
                            return true
                        }
                        return false
                    }
                    if (correlation[i][j] != correlation[j][i]) {
                        return false
                    }
                }
            }
            return true
        }

        /**
         * @param covariances the covariances matrix to check, must not be null
         * @return true if elements are valid covariance values
         */
        fun isValidCovariance(covariances: Array<DoubleArray>): Boolean {
            if (!KSLArrays.isSquare(covariances)) {
                return false
            }
            if (covariances.size <= 1) {
                return false
            }
            val diagonal: DoubleArray = KSLArrays.diagonal(covariances)
            if (!KSLArrays.isStrictlyPositive(diagonal)) {
                return false
            }
            for (i in covariances.indices) {
                for (j in covariances.indices) {
                    if (covariances[i][j] != covariances[j][i]) {
                        return false
                    }
                }
            }
            return true
        }

        /**
         * @param covariances the correlation matrix to convert
         * @return an array holding the correlations associated with the covariance matrix
         */
        fun convertToCorrelation(covariances: Array<DoubleArray>): Array<DoubleArray> {
            require(isValidCovariance(covariances)) { "The covariance array was not valid" }
            val s: DoubleArray = KSLArrays.diagonal(covariances) // variances extracted
            KSLArrays.apply(s) { a: Double -> sqrt(a) } // take square root to get standard deviations
            for (i in s.indices) {// invert the array values
                s[i] = 1.0 / s[i]
            }
            val d = MatrixUtils.createRealDiagonalMatrix(s)
            val cov = MatrixUtils.createRealMatrix(covariances)
            val result = d.multiply(cov).multiply(d)
            return result.data
        }

        /**
         * @param covariances a valid variance-covariance matrix
         * @return the Cholesky decomposition of the supplied matrix
         */
        fun choleskyDecomposition(covariances: Array<DoubleArray>): Array<DoubleArray> {
            require(isValidCovariance(covariances)) { "The covariance array was not valid" }
            // use of Apache Commons
            val cv = MatrixUtils.createRealMatrix(covariances)
            val cd = CholeskyDecomposition(cv)
            val lm = cd.getL()
            return lm.data
        }

    }
}

fun main() {
    val cov = arrayOf(
        doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0),
        doubleArrayOf(1.0, 2.0, 2.0, 2.0, 2.0),
        doubleArrayOf(1.0, 2.0, 3.0, 3.0, 3.0),
        doubleArrayOf(1.0, 2.0, 3.0, 4.0, 4.0),
        doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
    )
    val means = doubleArrayOf(10.0, 10.0, 10.0, 10.0, 10.0)
    val rv = MVNormalRV(means, cov)
    for (i in 1..5) {
        val sample: DoubleArray = rv.sample()
        println(KSLArrays.toCSVString(sample))
    }

    println()
    rv.correlations.write()
}
