package ksl.modeling.variable

import ksl.simulation.ModelElement
import ksl.utilities.Interval
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.statistic.WeightedStatistic
import ksl.utilities.statistic.WeightedStatisticIfc

// not subclassing from Variable, Response does not have an initial value, but does have limits
// should be observable
open class Response(
    parent: ModelElement,
    theLimits: Interval = Interval(),
    name: String?
) : ModelElement(parent, name), ResponseIfc, DefaultReportingOptionIfc, ResponseStatisticsIfc {

    val limits: Interval = theLimits

    init {
        //TODO("Response not implemented yet")
    }

    override var value: Double
        get() = TODO("Not yet implemented")
        set(value) {}

    override var previous: Double = Double.NaN
        get() = TODO("Not yet implemented")
        protected set

    override var timeOfChange: Double = Double.NaN
        get() = TODO("Not yet implemented")
        protected set

    override var previousTimeOfChange: Double = Double.NaN
        get() = TODO("Not yet implemented")
        protected set

    override val acrossReplicationStatistic: StatisticIfc = Statistic(name!!) //TODO

    override val withinReplicationStatistic: WeightedStatisticIfc = WeightedStatistic(name!!) //TODO

    override var defaultReportingOption: Boolean = true

}