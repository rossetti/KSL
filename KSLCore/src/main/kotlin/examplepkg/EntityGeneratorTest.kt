package examplepkg

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class EntityGeneratorTest(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val worker: ResourceWithQ = ResourceWithQ(this, "worker")
    private val tba = ExponentialRV(6.0, 1)
    private val st = RandomVariable(this, ExponentialRV(3.0, 2), "Service RV")
    private val wip = TWResponse(this, "${this.name}:WIP")
    private val tip = Response(this, "${this.name}:TimeInSystem")
    private val generator = EntityGenerator(::Customer, tba, tba)
    private val counter = Counter(this, "${this.name}:NumServed" )

    private inner class Customer: Entity() {
        val mm1: KSLProcess = process("MM1"){
            wip.increment()
            timeStamp = time
            val a  = seize(worker)
            delay(st)
            release(a)
            tip.value = time - timeStamp
            wip.decrement()
            counter.increment()
        }
    }
}

fun main(){
    val m = Model()
    val test = EntityGeneratorTest(m)
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
//    m.numberOfReplications = 1
//    m.lengthOfReplication = 20.0
//    m.lengthOfReplicationWarmUp = 5.0
    m.simulate()
    m.print()
}