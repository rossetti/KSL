package ksl.utilities.random.mcmc

import ksl.utilities.KSLArrays
import ksl.utilities.random.markovchain.DMarkovChain
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.Statistic

/**
 *  Randomly generates the states of a discrete Markov Chain for a Metropolis-Hastings process. Assumes that
 *  the states are labeled 1, 2, 3, etc.
 *  The transition probabilities are supplied as an array of arrays.
 *  transMatrix[0] holds the array of transition probabilities for transition to each state {p11, p12, p13, .., p1n} for state 1
 *  transMatrix[1] holds the array of transition probabilities for transition to each state {p21, p22, p23, .., p2n} for state 2
 *  etc.
 *  @param initialState the initial state
 *  @param proposalMatrix the single step transition matrix
 *  @param alphaMatrix the acceptance matrix
 * @param streamNumber the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 * @author rossetti
 */
class DMHMarkovChain(
    initialState: Int = 1,
    proposalMatrix: Array<DoubleArray>,
    private val alphaMatrix: Array<DoubleArray>,
    streamNumber: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : DMarkovChain(initialState, proposalMatrix, streamNumber, streamProvider, name) {

    init {
        require(KSLArrays.isSquare(alphaMatrix)) { "The acceptance matrix must be square" }
        require(alphaMatrix.size == proposalMatrix.size) { "Acceptance matrix must be size ${proposalMatrix.size}" }
    }

    val acceptanceMatrix
        get() = alphaMatrix.copyOf()

    private val myAcceptanceStat: Statistic = Statistic("Acceptance Statistics")

    /**
     *
     * @return statistics for the proportion of the proposed state (y) that are accepted
     */
    val acceptanceStatistics: Statistic
        get() = myAcceptanceStat.instance()

    override fun generate(): Double {
        val proposedState = KSLRandom.discreteInverseCDF(myStates, myCDFs[state - 1], rnStream)
        val acceptanceProb = alphaMatrix[state - 1][proposedState - 1]
        if (rnStream.randU01() <= acceptanceProb){
            state = proposedState
            myAcceptanceStat.collect(1.0)
        } else {
            myAcceptanceStat.collect(0.0)
        }
        return state.toDouble()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine(super.toString())
        sb.appendLine("Acceptance Matrix")
        for(array in alphaMatrix){
            sb.appendLine(array.joinToString())
        }
        sb.appendLine()
        sb.appendLine("Acceptance Statistics")
        sb.appendLine(myAcceptanceStat.asString())
        return sb.toString()
    }

}