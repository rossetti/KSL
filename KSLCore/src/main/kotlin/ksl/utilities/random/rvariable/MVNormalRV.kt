package ksl.utilities.random.rvariable


import ksl.utilities.KSLArrays
import ksl.utilities.random.rng.RNStreamIfc
import org.hipparchus.linear.CholeskyDecomposition
import org.hipparchus.linear.MatrixUtils
import java.util.*
import kotlin.math.sqrt

/**
 * Generations multi-dimensional normal random variates
 * @param means       the desired mean of the random variable, must not be null
 * @param covariances the covariance of the random variable
 * @param stream      the stream for sampling
 */
class MVNormalRV constructor(
    means: DoubleArray,
    covariances: Array<DoubleArray>,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : MVRVariableIfc {
    protected val covariances: Array<DoubleArray>
    protected val cfL: Array<DoubleArray> // Cholesky decomposition array

    override val dimension: Int
    protected val means: DoubleArray
    protected val normalRV: NormalRV

    init {
        require(isValidCovariance(covariances)) { "The covariance array was not valid" }
        dimension = covariances.size
        cfL = choleskyDecomposition(covariances)
        this.covariances = KSLArrays.copy2DArray(covariances)
        this.means = means.copyOf(means.size)
        normalRV = NormalRV(0.0, 1.0, stream)
    }

    override var rnStream: RNStreamIfc
        get() = normalRV.rnStream
        set(stream) {
            normalRV.rnStream = stream
        }

    override fun instance(stream: RNStreamIfc): MVRVariableIfc {
        return MVNormalRV(means, covariances, stream)
    }

    override fun antitheticInstance(): MVRVariableIfc {
        return MVNormalRV(means, covariances, normalRV.antitheticInstance().rnStream)
    }

    override fun sample(array: DoubleArray) {
        require(array.size == dimension) { "The length of the array was not the proper dimension" }
        val c: DoubleArray = KSLArrays.postProduct(cfL, normalRV.sample(dimension))
        val result: DoubleArray = KSLArrays.addElements(means, c)
        System.arraycopy(result, 0, array, 0, result.size)
    }

    override fun resetStartStream() {
        normalRV.resetStartStream()
    }

    override fun resetStartSubStream() {
        normalRV.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        normalRV.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = normalRV.antithetic
        set(flag) {
            normalRV.antithetic = flag
        }

    override var advanceToNextSubStreamOption: Boolean
        get() = normalRV.advanceToNextSubStreamOption
        set(b) {
            normalRV.advanceToNextSubStreamOption = b
        }

    override var resetStartStreamOption: Boolean
        get() = normalRV.resetStartStreamOption
        set(b) {
            normalRV.resetStartStreamOption = b
        }

    override fun toString(): String {
        val sb = StringBuilder("MVNormalRV")
        sb.append(System.lineSeparator())
        sb.append("nDim = ").append(dimension)
        sb.append(System.lineSeparator())
        sb.append("means = ")
        sb.append(System.lineSeparator())
        sb.append("[")
        sb.append(KSLArrays.toCSVString(means))
        sb.append("]")
        sb.append(System.lineSeparator())
        sb.append("covariances = ")
        sb.append(System.lineSeparator())
        for (i in covariances.indices) {
            sb.append("[")
            sb.append(KSLArrays.toCSVString(covariances[i]))
            sb.append("]")
            sb.append(System.lineSeparator())
        }
        sb.append("Cholesky decomposition = ")
        sb.append(System.lineSeparator())
        for (i in cfL.indices) {
            sb.append("[")
            sb.append(KSLArrays.toCSVString(cfL[i]))
            sb.append("]")
            sb.append(System.lineSeparator())
        }
        sb.append(System.lineSeparator())
        return sb.toString()
    }

    companion object {
        /**
         * @param means       the means for the distribution
         * @param stdDevs     an array holding the standard deviations
         * @param correlation the correlation matrix as an array
         * @return the created multi-variate normal
         */
        fun createRV(means: DoubleArray, stdDevs: DoubleArray, correlation: Array<DoubleArray>): MVNormalRV {
            val covariances = convertToCovariance(stdDevs, correlation)
            return MVNormalRV(means, covariances)
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
            stream: RNStreamIfc
        ): MVNormalRV {
            val covariances = convertToCovariance(stdDevs, correlation)
            return MVNormalRV(means, covariances, stream)
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
}
