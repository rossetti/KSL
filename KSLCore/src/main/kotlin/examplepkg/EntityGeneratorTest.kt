package examplepkg

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.Resource
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class EntityGeneratorTest(parent: ModelElement) : ProcessModel(parent, null) {

    private val worker: Resource = Resource(this, "worker")
    private val tba = RandomVariable(this, ExponentialRV(6.0, 1))
    private val st = RandomVariable(this, ExponentialRV(3.0, 2))
    private val wip = TWResponse(this, "${name}:WIP")
    private val tip = Response(this, "${name}:TimeInSystem")
    private val generator = EntityGenerator(::Customer, tba, tba)
//    private val arrivals = Arrivals()

    private inner class Customer: Entity() {
        val mm1: KSLProcess = process("MM1"){
            wip.increment()
            timeStamp = time
            val a  = seize(worker)
            delay(st)
            release(a)
            tip.value = time - timeStamp
            wip.decrement()
        }
    }

    override fun initialize() {
//        arrivals.schedule(tba)
    }

//    private inner class Arrivals: EventAction<Nothing>(){
//        override fun action(event: KSLEvent<Nothing>) {
//            val c = Customer()
////            println(c.processSequence)
////            activate(c.mm1)
//            startProcessSequence(c)
//            schedule(tba)
//        }
//    }

}

fun main(){
    //TODO minor statistical difference with SimpleProcessQ output
    val m = Model()
    val test = EntityGeneratorTest(m)
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
//    m.numberOfReplications = 1
//    m.lengthOfReplication = 20.0
//    m.lengthOfReplicationWarmUp = 5.0
    m.simulate()
}