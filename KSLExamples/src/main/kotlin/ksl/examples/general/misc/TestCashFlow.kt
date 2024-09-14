package ksl.examples.general.misc

import ksl.utilities.misc.CashFlow

fun main(){
    cashFlowTest1()
    cashFlowTest2()
}

fun cashFlowTest1() {
    val i = 0.10
    val n = 5
    val v = 100.0
    println("i = $i n = $n")
    println("F = " + v + " P given F = " + CashFlow.presentWorthGivenF(i, n, v))
    println("A = " + v + " P given A = " + CashFlow.presentWorthGivenA(i, n, v))
    println("P = " + v + " F given P = " + CashFlow.futureWorthGivenP(i, n, v))
    println("P = " + v + " A given P = " + CashFlow.annualizedWorthGivenP(i, n, v))
    println("A = " + v + " F given A = " + CashFlow.futureWorthGivenA(i, n, v))
    println("F = " + v + " A given F = " + CashFlow.annualizedWorthGivenF(i, n, v))
}

fun cashFlowTest2(){
    val r = 0.10
    val f = doubleArrayOf(-4000.0, 400.0, 400.0, 400.0, 400.0)
    val c = CashFlow(r, f)
    println(c)
    val f2 = doubleArrayOf(-5000.0, 1400.0, 4100.0, 4000.0, 200.0)
    val c2 = CashFlow(r, f2)
    println(c2)

    //884.27
    val p1: Double = CashFlow.presentWorthGivenF(r, 1, 1400.0)
    val p2: Double = CashFlow.presentWorthGivenF(r, 2, 4100.0)
    val p3: Double = CashFlow.presentWorthGivenF(r, 3, 4000.0)
    val p4: Double = CashFlow.presentWorthGivenF(r, 4, 200.0)
    val pw = -5000 + p1 + p2 + p3 + p4
    println("pw = $pw")
    val aw: Double = CashFlow.annualizedWorthGivenP(r, 4, pw)
    println("aw = $aw")
}