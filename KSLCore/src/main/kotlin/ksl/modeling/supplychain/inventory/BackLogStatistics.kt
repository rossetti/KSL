package ksl.modeling.supplychain.inventory

import ksl.modeling.supplychain.*

import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.statistic.WeightedStatisticIfc

/**
 * Statistic adapter that exposes a [BackLogQueue]'s queue-level
 * statistics (number-in-queue, time-in-queue) and the policy's
 * amount-backlogged statistics through [BackLogStatisticsIfc].
 *
 * The runtime casts on `numInQ` / `timeInQ` / `amtBackLoggedResponse`
 * are safe because [BackLogQueue] always constructs the concrete
 * KSL response types.
 *
 * See `sc.inventorylayer.BackLogStatistics`
 */
class BackLogStatistics(private val backLogQueue: BackLogQueue) : BackLogStatisticsIfc {

    private val numInQResponse: TWResponse =
        backLogQueue.queue.numInQ as TWResponse
    private val timeInQResponse: Response =
        backLogQueue.queue.timeInQ as Response
    private val amtBackLogged: TWResponse =
        backLogQueue.amtBackLoggedResponse as TWResponse

    override val numInQWithinReplication: WeightedStatisticIfc
        get() = numInQResponse.withinReplicationStatistic
    override val numInQAcrossReplications: StatisticIfc
        get() = numInQResponse.acrossReplicationStatistic

    override val timeInQWithinReplication: WeightedStatisticIfc
        get() = timeInQResponse.withinReplicationStatistic
    override val timeInQAcrossReplications: StatisticIfc
        get() = timeInQResponse.acrossReplicationStatistic

    override val amtBackLoggedWithinReplication: WeightedStatisticIfc
        get() = amtBackLogged.withinReplicationStatistic
    override val amtBackLoggedAcrossReplications: StatisticIfc
        get() = amtBackLogged.acrossReplicationStatistic
}
