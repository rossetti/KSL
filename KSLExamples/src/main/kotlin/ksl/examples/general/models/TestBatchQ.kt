package ksl.examples.general.models

import ksl.modeling.entity.BatchQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.DUniformRV
import ksl.utilities.random.rvariable.ExponentialRV


fun main(){
    val model = Model("Test Batching")
    val bTest = TestBatchQ(model)
    model.lengthOfReplication = 20.0
    model.numberOfReplications = 2
    model.simulate()
    model.print()
}

class TestBatchQ(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name){

    private val batchQ = BatchQueue<Customer>(this, defaultBatchSize = 3)
    init {
//        println("batchQ name:  ${batchQ.name}")
//        println("batchQ num in queue: ${batchQ.numInQ.name}")
//        println("batchQ time in queue: ${batchQ.timeInQ.name}")
    }
   // private val rv = RandomVariable(this, DUniformRV(1..4, 1))
    private val rv = RandomVariable(this, ExponentialRV(2.0, 1))

    private inner class Customer: BatchingEntity<Customer>() {
        val testBatching : KSLProcess = process("test") {
            println("$time >  customer ${this@Customer.name} arrived for batching")
//            delay(2.0, suspensionName = "Delay-for-10")
            if (waitedForBatch(this@Customer, batchQ)) {
                println("$time > customer ${this@Customer.name} is in batch, completed process")
                return@process
            }
            println("$time > customer ${this@Customer.name} has batch")
            for((bName, b) in this@Customer.batches){
                println("batch name $bName: elements : [${b.joinToString { it.name }}]")
            }
//            delay(2.0)
            println("$time >  customer ${this@Customer.name} completed batching routine")
        }
    }

    override fun initialize() {
        for (i in 1..10){
            schedule(this:: arrival, timeToEvent = rv.value)
        }
        println()
        println("replication: ${model.currentReplicationNumber}")
    }

    private fun arrival(event: KSLEvent<Nothing>){
        val c = Customer()
        activate(c.testBatching)
    }

}