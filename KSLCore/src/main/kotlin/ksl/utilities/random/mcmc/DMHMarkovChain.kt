package ksl.utilities.random.mcmc

import ksl.utilities.KSLArrays
import ksl.utilities.random.markovchain.DMarkovChain
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.Statistic

class DMHMarkovChain(
    initialState: Int = 1,
    proposalMatrix: Array<DoubleArray>,
    private val alphaMatrix: Array<DoubleArray>,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : DMarkovChain(initialState, proposalMatrix, stream) {
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