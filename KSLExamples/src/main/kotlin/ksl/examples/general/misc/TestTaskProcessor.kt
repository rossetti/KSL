package ksl.examples.general.misc

import ksl.modeling.elements.EventGenerator
import ksl.modeling.entity.TaskProcessingSystem
import ksl.modeling.queue.Queue
import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

fun main(){
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

    private val myTBA = RandomVariable(this, ExponentialRV(6.0, 1), "Arrival RV")
    private val myST = RandomVariable(this, ExponentialRV(3.0, 2), "Service RV")

    //private val myWaitingQ: Queue<Task> = Queue(this, "TaskQ")

    private val myTaskProcessor1 = TaskProcessorME(this, name = "TestProcessor1")
//    private val myTaskProcessor2 = TaskProcessorME(this, name = "TestProcessor2")
    private val myTaskDispatcher = TaskDispatcher(this, name = "Dispatcher")
    init {
        myTaskDispatcher.register(myTaskProcessor1)
//        myTaskDispatcher.register(myTaskProcessor2)
    }
    private val myArrivalGenerator: EventGenerator = EventGenerator(this, this::arrivals, myTBA, myTBA)

    private fun arrivals(generator: EventGenerator) {
        val task = WorkTask(myST)
        myTaskDispatcher.receive(task)
    }
}