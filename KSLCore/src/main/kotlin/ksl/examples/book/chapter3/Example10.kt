package ksl.examples.book.chapter3

import ksl.utilities.mcintegration.MCExperiment
import ksl.utilities.mcintegration.MCReplicationIfc
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.RVariableIfc

fun main() {
    val values = doubleArrayOf(5.0, 10.0, 40.0, 45.0, 50.0, 55.0, 60.0)
    val cdf = doubleArrayOf(0.1, 0.3, 0.6, 0.8, 0.9, 0.95, 1.0)
    val dCDF = DEmpiricalRV(values, cdf)
    val nv = NewsVendor(dCDF)
    val exp = MCExperiment(nv)
    exp.desiredHWErrorBound = 0.01
    exp.maxSampleSize = 250
    exp.runSimulation()
    println(exp)
}

class NewsVendor(var demand: RVariableIfc) : MCReplicationIfc {
    var orderQty = 30.0 // order qty
        set(value) {
            require(value > 0)
            field = value
        }
    var salesPrice = 0.25 //sales price
        set(value) {
            require(value > 0)
            field = value
        }
    var unitCost = 0.15 // unit cost
        set(value) {
            require(value > 0)
            field = value
        }
    var salvageValue = 0.02 //salvage value
        set(value) {
            require(value > 0)
            field = value
        }

    override fun replication(j: Int): Double {
        val d = demand.value
        val amtSold = minOf(d, orderQty)
        val amtLeft = maxOf(0.0, orderQty - d)
        return salesPrice * amtSold + salvageValue * amtLeft - unitCost * orderQty
    }

}