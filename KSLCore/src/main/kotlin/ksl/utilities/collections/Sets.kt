package ksl.utilities.collections

import kotlin.math.pow


infix fun Number.pow(power: Number): Double =
    this.toDouble().pow(power.toDouble())

infix fun Int.pow(power: Int): Int =
    this.toDouble().pow(power.toDouble()).toInt()

object Sets {

    fun <T> powerSet(set: Set<T>): Set<Set<T>> = set.powerSet()
    fun <T> powerSetSize(set: Set<T>): Int = set.powerSetSize

}

fun <T> Collection<T>.powerSet(): Set<Set<T>> = powerSet(this, setOf(setOf()))

private tailrec fun <T> powerSet(left: Collection<T>, acc: Set<Set<T>>): Set<Set<T>> = when {
    left.isEmpty() -> acc
    else ->powerSet(left.drop(1), acc + acc.map { it + left.first() })
}

val <T> Collection<T>.powerSetSize: Int
    get() = 2.pow(size)