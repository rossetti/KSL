package ksl.examples.general.variables

import ksl.modeling.variable.Response
import ksl.observers.ResponseTrace
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.simulation.SimulationReporter


class TestResponseVariable(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {
    private val myRS: Response = Response(this, "test constants")

    init {
        ResponseTrace(myRS)
    }

    override fun initialize() {
        schedule(this::doTest, 2.0);
    }

    private fun doTest(e: KSLEvent<Nothing>) {
        myRS.value = 2.0
        schedule(this::doTest, 2.0);
    }

}
fun main() {
    val sim = Model("test RS")
    TestResponseVariable(sim)
    val reporter: SimulationReporter = sim.simulationReporter
    reporter.turnOnReplicationCSVStatisticReporting()
    sim.numberOfReplications = 5
    sim.lengthOfReplication = 25.0
    sim.simulate()
    sim.print()
}