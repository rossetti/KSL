package ksl.utilities.random

import jsl.utilities.random.rvariable.JSLRandom
import ksl.utilities.random.rvariable.BetaRV
import ksl.utilities.random.rvariable.KSLRandom
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BetaTest {

    var bKSL: BetaRV? = null
    var bJSL: jsl.utilities.random.rvariable.BetaRV? = null
    @BeforeEach
    fun setUp() {
//        bJSL = jsl.utilities.random.rvariable.BetaRV(2.4, 5.5, 1)
//        bKSL = BetaRV(2.4, 5.5, 1)
    }

    @Test
    fun test1() {
        for (i in 1..10){
            val a = KSLRandom.rBeta(2.4, 5.5, 1)
            val b = JSLRandom.rBeta(2.4, 5.5, 1)
            println("KSL a = $a  JSL b = $b")
            Assertions.assertTrue(a == b)
        }
    }
}