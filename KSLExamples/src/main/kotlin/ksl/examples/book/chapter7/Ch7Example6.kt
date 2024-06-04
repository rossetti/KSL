package ksl.examples.book.chapter7

import ksl.modeling.elements.EventGenerator
import ksl.modeling.nhpp.NHPPEventGenerator
import ksl.modeling.nhpp.PiecewiseLinearRateFunction
import ksl.modeling.nhpp.PiecewiseRateFunction
import ksl.modeling.variable.Counter
import ksl.simulation.Model
import ksl.simulation.ModelElement

fun main() {
    val ar = doubleArrayOf(0.5, 0.5, 0.9, 0.9, 1.2, 0.9, 0.5)
    val dd = doubleArrayOf(200.0, 400.0, 400.0, 200.0, 300.0, 500.0)

    val f = PiecewiseLinearRateFunction(dd, ar)
    // create the experiment to run the model
    val s = Model()
    println("-----")
    println("intervals")
    System.out.println(f)
    NHPPPWLinearExample(s, f)

    // set the parameters of the experiment
    s.numberOfReplications = 1000
    s.lengthOfReplication = 2000.0

    // tell the simulation to run
    s.simulate()
    val r = s.simulationReporter
    r.printAcrossReplicationSummaryStatistics()
}

/**
 * @author rossetti
 */
class NHPPPWLinearExample(parent: ModelElement, f: PiecewiseRateFunction, name: String? = null) : ModelElement(parent, name) {

    private val myNHPPGenerator: NHPPEventGenerator = NHPPEventGenerator(this, f, this::arrivals, streamNum = 1)
    private val myCountersFC: MutableList<Counter> = mutableListOf()
    private val myPWRF: PiecewiseRateFunction = f

    init {
        val n: Int = f.numberSegments()
        for (i in 0 until n) {
            val c = Counter(this, "Interval FC $i")
            myCountersFC.add(c)
        }
    }

    private fun arrivals(generator: EventGenerator){
        val t: Double = time
        val i: Int = myPWRF.findTimeInterval(t)
        myCountersFC[i].increment()
    }
}