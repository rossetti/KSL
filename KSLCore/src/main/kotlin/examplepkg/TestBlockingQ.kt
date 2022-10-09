package examplepkg

import ksl.modeling.entity.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

class TestBlockingQ(parent: ModelElement) : ProcessModel(parent, null) {

    val blockingQ: BlockingQueue<QObject> = BlockingQueue(this)
//    val blockingQ: BlockingQueue<QObject> = BlockingQueue(this, capacity = 10)
    private inner class Receiver: Entity() {
        val receiving : KSLProcess = process("receiving") {
            for (i in 1..15) {
                println("time = $time before the first delay in ${this@Receiver}")
                delay(1.0)
                println("time = $time trying to get item")
                waitForItems(blockingQ)
                println("time = $time after getting item")
                delay(5.0)
                println("time = $time after the second delay in ${this@Receiver}")
            }
            println("time = $time exiting the process in ${this@Receiver}")
        }
    }

    private inner class Sender: Entity() {
        val sending : KSLProcess = process("sending") {
            for (i in 1..15){
                delay(5.0)
                println("time = $time after the first delay in ${this@Sender}")
                val item = QObject()
                println("time = $time before sending an item")
                send(item, blockingQ)
                println("time = $time after sending an item")
            }
        }
    }

    override fun initialize() {
        val r = Receiver()
        activate(r.receiving)
        val s = Sender()
        activate(s.sending)
    }

}

fun main(){
    val m = Model()
    val test = TestBlockingQ(m)

    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
}