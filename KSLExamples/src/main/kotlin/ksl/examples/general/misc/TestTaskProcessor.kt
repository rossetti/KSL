package ksl.examples.general.misc

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.EventGeneratorIfc
import ksl.modeling.entity.TaskProcessingSystem
import ksl.modeling.queue.Queue
import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {
    val m = Model()
    val test = TestTaskProcessor(m)
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}

class TestTaskProcessor(
    parent: ModelElement,
    name: String? = null
) : TaskProcessingSystem(parent, name) {

    private val myTBA = ExponentialRV(6.0, 1)
    private val myST = RandomVariable(this, ExponentialRV(3.0, 2), "Service RV")

    //    private val myTaskProcessor1 = TransientTaskProcessor(name = "TestProcessor1")
    private val myTaskProcessor1 = TaskProcessor(this, name = "TestProcessor1")

    //    private val myTaskProcessor2 = TaskProcessor(this, name = "TestProcessor2")
    private val myTaskDispatcher = TaskDispatcher(this, name = "Dispatcher")

    init {
        myTaskDispatcher.register(myTaskProcessor1)
//        myTaskDispatcher.register(myTaskProcessor2)
//        myTaskDispatcher.configureTransientTaskProcessorPerformance()
    }

    private val myArrivalGenerator: EventGenerator = EventGenerator(this, this::arrivals, myTBA, myTBA)

    private fun arrivals(generator: EventGeneratorIfc) {
        val task = WorkTask(myST)
        myTaskDispatcher.receive(task)
    }
}