package ksl.examples.book.chapter5

/**
 *  Example 5.3
 *  This example and the LindleyEquation class illustrate how to
 *  implement the Lindley equation using the KSL.
 */
fun main(){

    val le = LindleyEquation()

    le.simulate()

    le.print()
}
