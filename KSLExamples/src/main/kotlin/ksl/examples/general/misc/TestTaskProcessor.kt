package ksl.examples.general.misc

import ksl.modeling.elements.EventGenerator
import ksl.modeling.entity.TaskProcessingSystem
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
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

    private val myWaitingQ: Queue<Task> = Queue(this, "TaskQ")

    private val myTaskProcessor = TaskProcessorME(this, name = "TestProcessor")
    private val myTaskProvider = QueueBasedTaskProvider(myWaitingQ)
    init {
        myTaskProvider.register(myTaskProcessor)
    }
    private val myArrivalGenerator: EventGenerator = EventGenerator(this, this::arrivals, myTBA, myTBA)

    private fun arrivals(generator: EventGenerator) {
        val task = WorkTask(myST)
        myTaskProvider.receive(task)
    }
}