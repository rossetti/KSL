package ksl.utilities.random.mcmc

import ksl.utilities.random.rng.RNStreamChangeIfc
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.Statistic

class MetropolisHastingMV(
    theInitialX: DoubleArray,
    theTargetFun: FunctionMVIfc,
    theProposalFun: ProposalFunctionMVIfc,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) :
    RNStreamControlIfc by stream, RNStreamChangeIfc {

    override var rnStream: RNStreamIfc = stream
    private val targetFun = theTargetFun
    private val proposalFun = theProposalFun
    private val initialX = theInitialX.copyOf()
    var initializedFlag = false
        protected set

    var burnInFlag = false
        protected set

    protected var myCurrentX: DoubleArray = DoubleArray(initialX.size)

    protected var myProposedY: DoubleArray = DoubleArray(initialX.size)

    protected var myPrevX: DoubleArray = DoubleArray(initialX.size)

    protected var myLastAcceptanceProbability = 0.0

    protected var myFofProposedY = 0.0

    protected var myFofCurrentX = 0.0

    val acceptanceStatistics: Statistic = Statistic("Acceptance Statistics")
        get() = field.instance()

    private val myObservedStatistics: List<Statistic> = buildList {
        for (i in initialX.indices) {
            this[i] = Statistic("X:" + (i + 1))
        }
    }

    fun observedStatistics(): List<Statistic> {
        val mutableList = mutableListOf<Statistic>()
        for (statistic in myObservedStatistics) {
            mutableList.add(statistic.instance())
        }
        return mutableList
    }
    
}