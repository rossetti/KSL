package ksl.modeling.supplychain

import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.statistic.WeightedStatisticIfc

/**
 * Read-only view of statistics for a backlog queue: per-replication
 * weighted statistics and across-replication summary statistics for
 * number-in-queue, time-in-queue, and amount-backlogged.
 *
 * See `sc.inventorylayer.BackLogStatisticsIfc`
 */
interface BackLogStatisticsIfc {
    val numInQWithinReplication: WeightedStatisticIfc
    val numInQAcrossReplications: StatisticIfc

    val timeInQWithinReplication: WeightedStatisticIfc
    val timeInQAcrossReplications: StatisticIfc

    val amtBackLoggedWithinReplication: WeightedStatisticIfc
    val amtBackLoggedAcrossReplications: StatisticIfc
}
