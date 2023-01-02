package ksl.examples.book.chapter7

import ksl.modeling.nhpp.PiecewiseLinearRateFunction
import ksl.simulation.Model

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