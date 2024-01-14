package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.RVariableIfc

class TruncatedNormal(
    private val normal: Normal,
    interval: Interval,
    name: String? = null
) : Distribution<TruncatedNormal>(name), ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc {

    init {
        require(interval.contains(normal.mean)) {"The mean value was not within the truncation interval."}
    }

    private val myInterval = interval.instance()

    val interval
        get() = myInterval.instance()

    var normalMean: Double
        get() = normal.mean
        set(value) {
            require(myInterval.contains(value)) {"The mean value was not within the truncation interval."}
            normal.mean = value
        }

    var normalVariance : Double
        get() = normal.variance
        set(value) {
            require(value > 0) { "Variance must be positive" }
            normal.variance = value
        }

    override fun cdf(x: Double): Double {
        TODO("Not yet implemented")
    }

    override fun pdf(x: Double): Double {
        TODO("Not yet implemented")
    }

    override fun mean(): Double {
        TODO("Not yet implemented")
    }

    override fun variance(): Double {
        TODO("Not yet implemented")
    }

    override fun domain(): Interval {
        TODO("Not yet implemented")
    }

    override fun invCDF(p: Double): Double {
        TODO("Not yet implemented")
    }

    override fun parameters(params: DoubleArray) {
        TODO("Not yet implemented")
    }

    override fun parameters(): DoubleArray {
        TODO("Not yet implemented")
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        TODO("Not yet implemented")
    }

    override fun instance(): TruncatedNormal {
        TODO("Not yet implemented")
    }

}