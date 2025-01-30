package ksl.examples.general.lectures.week13

import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.DUniformRV
import ksl.utilities.random.rvariable.ExponentialRV

fun main(){
    val model = Model("MfgSystem Example")
    val mfgSystem = MfgSystem(model, "MfgSystem")
    model.lengthOfReplication = 5000.0
    model.numberOfReplications = 2
    model.simulate()
    model.print()
}

/**
 *  The purpose of this example is to illustrate how to construct and use a component
 *  and to communicate information when a process completes.
 *  Orders have a random number of parts to produce using two different assembly lines.
 *  A part is randomly assigned to an assembly line for processing.
 *  Each assembly line consists of a sequence of stations to visit.
 */
class MfgSystem(
    parent: ModelElement,
    name: String?
) : ProcessModel(parent, name) {

    private val line1 = AssemblyLine(this, "${this.name}:Line1")
    private val line2 = AssemblyLine(this, "${this.name}:Line2")

    private val lineList = listOf(line1, line2)
    private val cdf = doubleArrayOf(0.4, 1.0)
    private val rLineList = REmpiricalList(this, lineList, cdf)

    private val r1 = RandomVariable(this, ExponentialRV(10.0))
    private val r2 = RandomVariable(this, ExponentialRV(12.0))
    private val r3 = RandomVariable(this, ExponentialRV(14.0))

    private val tba = RandomVariable(this, ExponentialRV(15.0))
    private val orderSizeRV = RandomVariable(this, DUniformRV(5, 10))

    private val systemTime = Response(this, "${this.name}:SystemTime")
    private val orderCounter = Counter(this, "${this.name}:OrderCounter")
    private val partCounter = Counter(this, "${this.name}:PartCounter")

    override fun initialize() {
        // schedule the first arrival
        schedule(this::arrivals, tba)
    }

    private fun arrivals(event: KSLEvent<Nothing>) {
        // make the order
        val order = Order()
        // start the part
        activate(order.orderProcess)
        // schedule the next arrival
        schedule(this::arrivals, tba)
    }

    private inner class AssemblyLine(
        system: MfgSystem,
        name: String?
    ) : ModelElement(system, name) {

        val station1 = ResourceWithQ(this, "${this.name}:Station1")
        val station2 = ResourceWithQ(this, "${this.name}:Station2")
        val station3 = ResourceWithQ(this, "${this.name}:Station3")

    }

    private inner class Order : Entity() {
        val parts = mutableListOf<Part>()
        init {
            val orderSize = orderSizeRV.value.toInt()
            for (i in 0 until orderSize) {
                // randomly pick the line for the part
                val line = rLineList.randomElement
                parts.add(Part(this, line))
            }
        }

        val orderProcess = process ("Order Process") {
            for(part in parts) {
                // waitFor causes the order to wait for the part to be completed
                // before the next part is activated.
                waitFor(part.mfgProcess)
            }
        }

        override fun afterRunningProcess(completedProcess: KSLProcess) {
            // can decide what to do based on the type of completed process
            orderCompleted(this)
        }
    }

    private fun orderCompleted(order: Order) {
        // can do whatever you want after the order is completed
        orderCounter.increment()
        systemTime.value = time - order.createTime
    }

    private inner class Part(
        val order: Order,
        var line: AssemblyLine
    ) : Entity() {

        val mfgProcess = process ("Part Process ${line.name}"){
            use(line.station1, delayDuration = r1)
            use(line.station2, delayDuration = r2)
            use(line.station3, delayDuration = r3)
        }

        override fun afterRunningProcess(completedProcess: KSLProcess) {
            // can decide what to do based on the type of completed process
            partCompleted(this)
        }

    }

    private fun partCompleted(part: Part) {
        // can do whatever you want after a part is completed
        partCounter.increment()
    }

}