package ksl.examples.general.misc

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun main() {
    testSuspend3()
}

suspend fun testSuspend(){
    println("Before")

    suspendCancellableCoroutine<Unit> { continuation ->
        println("Before too")
        continuation.resume(Unit)
    }

    println("After")
}

suspend fun testSuspend2(){
    println("Before")

    suspendCancellableCoroutine<Unit>() { continuation ->
        println("Before too")
        continuation.resume(Unit)
    }

    println("After")
}

suspend fun testSuspend3(){
    println("Before")

    suspendCancellableCoroutine<Unit>() { holdsContinuation(it) }

    println("After")
}

/** note, it is not a suspending function
 *  The lambda function is just a function that will (eventually)
 *  resume the coroutine
 */
fun holdsContinuation(continuation: Continuation<Unit>) {
    println("Before too")
    continuation.resume(Unit)
}


