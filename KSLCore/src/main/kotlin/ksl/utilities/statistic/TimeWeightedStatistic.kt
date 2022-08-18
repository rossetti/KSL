package ksl.utilities.statistic

import ksl.utilities.GetTimeIfc

class TimeWeightedStatistic (
    timeGetter: GetTimeIfc,
    initialValue: Double = 0.0,
    initialTime: Double = 0.0
) : Collector(), WeightedStatisticIfc {

    private val myTimeGetter: GetTimeIfc
    private var myLastValue: Double
    private var myLastTime: Double
    private val myWeightedStatistic: WeightedStatistic
    var updateTimeAtReset = true

    init {
        require(initialTime >= 0.0) { "The initial time must be >= 0.0" }
        require(initialValue >= 0.0) { "The initial value must be >= 0.0" }
        myTimeGetter = timeGetter
        myWeightedStatistic = WeightedStatistic()
        myLastTime = initialTime
        myLastValue = initialValue
    }

    override fun collect(obs: Double) {
        val time: Double = myTimeGetter.time()
        val weight = time - myLastTime
        myLastTime = time
        //        System.out.println("time = " + time + " value = " + value + " last  = " + myLastValue + " weight = " + weight);
        myWeightedStatistic.collect(myLastValue, weight)
        myLastValue = obs
    }

    override val count: Double
        get() = myWeightedStatistic.count
    override val max: Double
        get() = myWeightedStatistic.max
    override val min: Double
        get() = myWeightedStatistic.min
    override val sumOfWeights: Double
        get() = myWeightedStatistic.sumOfWeights
    override val weightedAverage: Double
        get() = myWeightedStatistic.weightedAverage
    override val weightedSum: Double
        get() = myWeightedStatistic.weightedSum
    override val weightedSumOfSquares: Double
        get() = myWeightedStatistic.weightedSumOfSquares

    override fun reset() {
        myWeightedStatistic.reset()
        if (updateTimeAtReset) {
            myLastTime = myTimeGetter.time()
        }
    }

    override val lastWeight: Double
        get() = myWeightedStatistic.lastWeight
    override val numberMissing: Double
        get() = myWeightedStatistic.numberMissing
    override val unWeightedSum: Double
        get() = myWeightedStatistic.unWeightedSum

    val statistics: DoubleArray
        get() = myWeightedStatistic.statistics

    override fun toString(): String {
        return myWeightedStatistic.toString()
    }

    override val csvStatistic: String
        get() = myWeightedStatistic.csvStatistic

    override val csvStatisticHeader: String
        get() = myWeightedStatistic.csvStatisticHeader

}

fun main() {
    val t = doubleArrayOf(0.0, 2.0, 5.0, 11.0, 14.0, 17.0, 22.0, 26.0, 28.0, 31.0, 35.0, 36.0)
    val n = doubleArrayOf(0.0, 1.0, 0.0, 1.0, 2.0, 3.0, 4.0, 3.0, 2.0, 1.0, 0.0, 0.0)
    val tws = TimeWeightedStatistic(TimeArray(t))
    for (x in n) {
        tws.collect(x)
    }
    println(tws)
}

class TimeArray(var timeValues: DoubleArray) : GetTimeIfc {
    private var index = -1
    override fun time(): Double {
        if (index < timeValues.size - 1) {
            index = index + 1
            return timeValues[index]
        }
        return timeValues[index]
    }

    fun reset() {
        index = -1
    }
}