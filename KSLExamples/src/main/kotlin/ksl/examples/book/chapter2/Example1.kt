package ksl.examples.book.chapter2

import ksl.utilities.random.rng.RNStreamProvider


/**
 * This example illustrates how to make a stream provider, get streams
 * from the provider, and use the streams to generate pseudo-random
 * numbers.
 */
fun main() {
    // make a provider for creating streams
    val p1 = RNStreamProvider()
    // get the first stream from the provider
    val p1s1 = p1.nextRNStream()
    // make another provider, the providers are identical
    val p2 = RNStreamProvider()
    // thus the first streams returned are identical
    val p2s1 = p2.nextRNStream()
    System.out.printf("%3s %15s %15s %n", "n", "p1s1", "p2s2")
    for (i in 1..10) {
        System.out.printf("%3d %15f %15f %n", i, p1s1.randU01(), p2s1.randU01())
    }
}