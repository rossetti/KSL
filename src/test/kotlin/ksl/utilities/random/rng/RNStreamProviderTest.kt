package ksl.utilities.random.rng

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

internal class RNStreamProviderTest {

    private var f: ksl.utilities.random.rng.RNStreamFactory? = null

    @BeforeEach
    fun setUp() {
        println("Making new factory.")
        f = ksl.utilities.random.rng.RNStreamFactory()
        println(f)
    }

    @AfterEach
    fun tearDown() {
    }

    @Test
    fun testAgainstJSL(){
        val jsl = buildJSLRNGString()
        val ksl = buildKSLRNGString()
        println("-----------------------------------------------------")
        println("The test program should print:")
        println(jsl)
        assertEquals(jsl, ksl)
        println()
    }

    private fun buildJSLRNGString(): String {
        val s = StringBuilder()
        val provider = jsl.utilities.random.rng.RNStreamProvider()
        val s1 = provider.defaultRNStream()
        s.appendLine("Default stream is stream 1")
        s.appendLine("Generate 3 random numbers")
        for (i in 1..3) {
            s.appendLine("u = " + s1.randU01())
        }
        s1.advanceToNextSubstream()
        s.appendLine("Advance to next sub-stream and get some more random numbers")
        for (i in 1..3) {
            s.appendLine("u = " + s1.randU01())
        }
        s.appendLine("Notice that they are different from the first 3.")
        s1.resetStartStream()
        s.appendLine("Reset the stream to the beginning of its sequence")
        for (i in 1..3) {
            s.appendLine("u = " + s1.randU01())
        }
        s.appendLine("Notice that they are the same as the first 3.")
        s.appendLine("Get another random number stream")
        val s2 = provider.nextRNStream()
        s.appendLine("2nd stream")
        for (i in 1..3) {
            s.appendLine("u = " + s2.randU01())
        }
        s.appendLine("Notice that they are different from the first 3.")
        return s.toString()
    }

    private fun buildKSLRNGString(): String {
        val s = StringBuilder()
        val provider = RNStreamProvider()
        val s1 = provider.defaultRNStream()
        s.appendLine("Default stream is stream 1")
        s.appendLine("Generate 3 random numbers")
        for (i in 1..3) {
            s.appendLine("u = " + s1.randU01())
        }
        s1.advanceToNextSubStream()
        s.appendLine("Advance to next sub-stream and get some more random numbers")
        for (i in 1..3) {
            s.appendLine("u = " + s1.randU01())
        }
        s.appendLine("Notice that they are different from the first 3.")
        s1.resetStartStream()
        s.appendLine("Reset the stream to the beginning of its sequence")
        for (i in 1..3) {
            s.appendLine("u = " + s1.randU01())
        }
        s.appendLine("Notice that they are the same as the first 3.")
        s.appendLine("Get another random number stream")
        val s2 = provider.nextRNStream()
        s.appendLine("2nd stream")
        for (i in 1..3) {
            s.appendLine("u = " + s2.randU01())
        }
        s.appendLine("Notice that they are different from the first 3.")
        return s.toString()
    }

    @Test
    fun test1() {
        val rm = ksl.utilities.random.rng.RNStreamFactory()
        val rng = rm.nextStream()
        var sum = 0.0
        val n = 1000
        for (i in 1..n) {
            sum = sum + rng.randU01()
        }
        println("-----------------------------------------------------")
        println("This test program should print the number   490.9254839801")
        println("Actual test result = $sum")
        assertEquals(sum, 490.9254839801)
        println()
    }

    @Test
    fun test2() {
        // test the advancement of streams
        val count = 100
        val advance = 20
        val rm = ksl.utilities.random.rng.RNStreamFactory()
        rm.advanceSeeds(advance)
        val rng = rm.nextStream()
        var sum = 0.0
        for (i in 1..count) {
            sum = sum + rng.randU01()
        }
        println("-----------------------------------------------------")
        println("This test program should print the number   55.445704270784404")
        println("Actual test result = $sum")
        assertEquals(sum ,55.445704270784404)
        println()
    }

    @Test
    fun subStreamTest() {
        // test the advancement of sub streams
        val count = 100
        val advance = 20
        val rm = ksl.utilities.random.rng.RNStreamFactory()
        val rng = rm.nextStream()
        for (i in 0 until advance) {
            rng.advanceToNextSubStream()
        }
        var sum = 0.0
        for (i in 1..count) {
            sum = sum + rng.randU01()
        }
        println("-----------------------------------------------------")
        println("This test program should print the number   49.28122645558211")
        println("Actual test result = $sum")
        assertEquals(sum, 49.28122645558211)
        println()
    }

    @Test
    fun test3() {
        val g1: ksl.utilities.random.rng.RNStreamIfc = f!!.nextStream()
        val g2: ksl.utilities.random.rng.RNStreamIfc = f!!.nextStream()
        println("Two different streams from the same factory.")
        println("Note that they produce different random numbers")
        var s1 = 0.0
        var s2 = 0.0
        var u1: Double
        var u2: Double
        for (i in 0..4) {
            u1 = g1.randU01()
            u2 = g2.randU01()
            s1 = s1 + u1
            s2 = s2 + u2
            println("u1 = $u1\t u2 = $u2")
        }
        val t1 = s1
        val t2 = s2
        assertNotEquals(s1, s2)
        println()
        g1.resetStartStream()
        g2.resetStartStream()
        println("Resetting to the start of each stream simply")
        println("causes them to repeat the above.")
        s1 = 0.0
        s2 = 0.0
        for (i in 0..4) {
            u1 = g1.randU01()
            u2 = g2.randU01()
            s1 = s1 + u1
            s2 = s2 + u2
            println("u1 = $u1\t u2 = $u2")
        }
        assertNotEquals(s1, s2)
        g1.advanceToNextSubStream()
        g1.advanceToNextSubStream()
        println("Advancing to the start of the next substream ")
        println("causes them to advance to the beginning of the next substream.")
        s1 = 0.0
        s2 = 0.0
        for (i in 0..4) {
            u1 = g1.randU01()
            u2 = g2.randU01()
            s1 = s1 + u1
            s2 = s2 + u2
            println("u1 = $u1\t u2 = $u2")
        }
        assertNotEquals(s1, s2)
        g1.resetStartStream()
        g2.resetStartStream()
        g1.antithetic = true
        g2.antithetic = true
        println("Resetting to the start of the stream and turning on antithetic")
        println("causes them to produce the antithetics for the original starting stream.")
        s1 = 0.0
        s2 = 0.0
        for (i in 0..4) {
            u1 = g1.randU01()
            u2 = g2.randU01()
            s1 = s1 + (1.0 - u1)
            s2 = s2 + (1.0 - u2)
            println("u1 = $u1\t u2 = $u2")
        }
        assertEquals(s1, t1)
        assertEquals(s2, t2)
        println()
    }

    @Test
    fun test5() {
        // factories produce the same streams
        val f1 = ksl.utilities.random.rng.RNStreamFactory()
        val f2 = ksl.utilities.random.rng.RNStreamFactory()
        val g1f1 = f1.nextStream()
        val g1f2 = f2.nextStream()
        println()
        println("**********************************************")
        println("Test 5: Factories produce same streams")
        println(f1)
        println(f2)
        println(g1f1)
        println(g1f2)
        println("Generate from both")
        var flag = true
        for (i in 1..10) {
            val u1 = g1f1.randU01()
            val u2 = g1f2.randU01()
            println("u1 = $u1\t u2 = $u2")
            if (u1 != u2) {
                flag = false
            }
        }
        println("Test passes if all generated are the same")
        println("**********************************************")
        assert(flag)
    }

    @Test
    fun test8() {
        println()
        println("**********************************************")
        println("Test 8")
        println("Make a factory")
        val f1 = ksl.utilities.random.rng.RNStreamFactory()
        println(f1)
        println("Make a stream from f1")
        val rngf1 = f1.nextStream() as ksl.utilities.random.rng.RNStreamFactory.RNStream
        println(rngf1)
        println("Generate 5 numbers from rngf1")
        for (i in 1..5) {
            println(rngf1.randU01())
        }
        println(rngf1)
        println("Current state of f1")
        println(f1)
        println("Clone the stream")
        val rngf2 = rngf1.instance("clone of rngf1") as ksl.utilities.random.rng.RNStreamFactory.RNStream
        println(rngf2)
        val s1 = rngf1.state()
        val s2 = rngf2.state()
        var b = true
        for (i in s1.indices) {
            b = b && s1[i] == s2[i]
        }
        Assertions.assertTrue(b)
    }
}