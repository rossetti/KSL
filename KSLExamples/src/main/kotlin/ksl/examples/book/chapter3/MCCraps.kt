package ksl.examples.book.chapter3

import ksl.utilities.random.markovchain.DMarkovChain
import ksl.utilities.statistic.Statistic

fun main() {
    val win = 6
    val loss = 5
    val pw = Statistic("Prob(Win)")
    val pl = Statistic("Prob(Loss)")
    val mc = makeMC()
    val numGames = 5000
    for (i in 1..numGames) {
        mc.reset()
        var cs = mc.initialState
        var done = false
        while (!done) {
            cs = mc.nextState()
            if ((cs == loss) || (cs == win)) {
                pw.collect(cs == win)
                pl.collect(cs == loss)
                done = true
            }
        }
    }
    println(pw)
    println(pl)
}

fun makeMC() : DMarkovChain {
    val transitionMatrix = arrayOf<DoubleArray>(
        doubleArrayOf(0.0, 6.0/36.0, 8.0/36.0, 10.0/36.0, 4.0/36.0, 8.0/36.0),
        doubleArrayOf(0.0, 27.0/36.0, 0.0, 0.0, 6.0/36.0, 3.0/36.0),
        doubleArrayOf(0.0, 0.0, 26.0/36.0, 0.0, 6.0/36.0, 4.0/36.0),
        doubleArrayOf(0.0, 0.0, 0.0, 25.0/36.0, 6.0/36.0, 5.0/36.0),
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
    )
    val mc = DMarkovChain(theInitialState = 1, transitionMatrix)
    println(mc)
    println()
    return mc
}