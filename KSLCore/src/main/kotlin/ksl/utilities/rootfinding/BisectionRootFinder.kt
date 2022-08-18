package ksl.utilities.rootfinding

import ksl.utilities.Interval
import ksl.utilities.math.FunctionIfc
import ksl.utilities.math.KSLMath
import kotlin.math.abs

class BisectionRootFinder(
    func: FunctionIfc,
    interval: Interval,
    initialPoint: Double = (interval.lowerLimit + interval.upperLimit) / 2.0,
    maxIter: Int = 100,
    desiredPrec: Double = KSLMath.defaultNumericalPrecision
) : RootFinder(func, interval, initialPoint, maxIter, desiredPrec) {

    override fun evaluateIteration(): Double {
        result = (xPos + xNeg) * 0.5
        if (func.f(result) > 0) {
            xPos = result
        } else {
            xNeg = result
        }
        return relativePrecision(abs(xPos - xNeg))
    }

    override fun finalizeIterations() {
    }

    override fun initializeIterations() {
    }
}

fun main(){
    val f = FunctionIfc { x -> x * x * x + 4.0 * x * x - 10.0 }

    val b = BisectionRootFinder(f, Interval(1.0, 2.0))

    b.evaluate()

    println(b)
}