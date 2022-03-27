package ksl.utilities.math

import jsl.utilities.math.JSLMath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class TestKSLMath {

    @BeforeEach
    fun setUp() {
        println("Testing KSLMath.")
    }

    @AfterEach
    fun tearDown() {
    }

    @Test
    fun testAgainstJSL(){
        println(KSLMath)
        println()
        JSLMath.printParameters(System.out)
        println()
        assert(JSLMath.binomialCoefficient(10,3) == KSLMath.binomialCoefficient(10, 3))
        println()
        assert(JSLMath.logFactorial(100) == KSLMath.logFactorial(100))
        println()
        assert(JSLMath.factorial(100) == KSLMath.factorial(100))
    }
}