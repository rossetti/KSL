package ksl.examples.general.variables.nhpp

import ksl.modeling.elements.EventGeneratorIfc
import ksl.modeling.elements.GeneratorActionIfc
import ksl.modeling.nhpp.NHPPEventGenerator
import ksl.modeling.nhpp.PiecewiseConstantRateFunction
import ksl.modeling.nhpp.PiecewiseRateFunction
import ksl.modeling.variable.Counter
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.divideConstant

/**
 * @author rossetti
 */
class TestSTEMFairArrivals(
    parent: ModelElement,
    f: PiecewiseRateFunction,
    name: String? = null
) :
    ModelElement(parent, name) {
    private val myListener: EventListener = EventListener()
    private val myNHPPGenerator: NHPPEventGenerator = NHPPEventGenerator(
        this,
        myListener, f, streamNum = 1
    )
    private val myCountersFC: MutableList<Counter> = mutableListOf()
    private val myPWRF: PiecewiseRateFunction = f

    init {
        val n: Int = f.numberSegments()
        for (i in 0 until n) {
            val c = Counter(this, "Interval FC $i")
            myCountersFC.add(c)
        }
    }

    private inner class EventListener : GeneratorActionIfc {
        override fun generate(generator: EventGeneratorIfc) {
            val t: Double = time
            val i: Int = myPWRF.findTimeInterval(t)
            myCountersFC[i].increment()
//            if (t >= 120.0){
//                myNHPPGenerator.turnOffGenerator()
//            }
        }
    }
}

fun main() {

    // create the experiment to run the model
    val s = Model("TestSTEMFairArrivals")

    // set up the generator
    val durations = doubleArrayOf(
        30.0, 30.0, 30.0, 30.0, 30.0, 30.0,
        30.0, 30.0, 30.0, 30.0, 30.0, 30.0
    )
    val hourlyRates = doubleArrayOf(
        5.0, 10.0, 15.0, 25.0, 40.0, 50.0,
        55.0, 55.0, 60.0, 30.0, 5.0, 5.0
    )
    val ratesPerMinute = hourlyRates.divideConstant(60.0)

    val f = PiecewiseConstantRateFunction(durations, hourlyRates)
    println("-----")
    println("intervals")
    println(f)
    TestSTEMFairArrivals(s, f)

    // set the parameters of the experiment
    s.numberOfReplications = 100
    s.lengthOfReplication = 6.0*60.0

    // tell the simulation to run
    s.simulate()
    val r = s.simulationReporter
    r.printAcrossReplicationSummaryStatistics()
}

