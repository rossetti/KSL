package ksl.modeling.supplychain

import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.statistic.WeightedStatisticIfc

/**
 * Read-only view of per-inventory statistics, exposing both
 * within-replication weighted statistics and across-replication
 * statistics for each tracked quantity.
 *
 * @see sc.inventorylayer.InventoryStatisticsIfc
 */
interface InventoryStatisticsIfc {
    val onHandWithinReplication: WeightedStatisticIfc
    val onHandAcrossReplications: StatisticIfc

    val stockOutIndicatorWithinReplication: WeightedStatisticIfc
    val stockOutIndicatorAcrossReplications: StatisticIfc

    val onOrderWithinReplication: WeightedStatisticIfc
    val onOrderAcrossReplications: StatisticIfc

    val firstFillRateWithinReplication: WeightedStatisticIfc
    val firstFillRateAcrossReplications: StatisticIfc

    val orderCounterAcrossReplications: StatisticIfc

    /** Current count of replenishment orders observed this replication. */
    val orderCounterWithinReplication: Double

    val timeBtwOrdersWithinReplication: WeightedStatisticIfc
    val timeBtwOrdersAcrossReplications: StatisticIfc

    val timeBtwDemandsWithinReplication: WeightedStatisticIfc
    val timeBtwDemandsAcrossReplications: StatisticIfc

    val demandSizeWithinReplication: WeightedStatisticIfc
    val demandSizeAcrossReplications: StatisticIfc

    val orderAmountWithinReplication: WeightedStatisticIfc
    val orderAmountAcrossReplications: StatisticIfc

    val backLogStatistics: BackLogStatisticsIfc
}
