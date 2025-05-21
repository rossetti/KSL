package ksl.utilities.collections

import kotlin.math.pow


infix fun Number.pow(power: Number): Double =
    this.toDouble().pow(power.toDouble())

infix fun Int.pow(power: Int): Int =
    this.toDouble().pow(power.toDouble()).toInt()

object Sets {

    fun <T> powerSet(set: Set<T>): Set<Set<T>> = set.powerset()
    fun <T> powerSetSize(set: Set<T>): Int = set.powersetSize

}

fun <T> Collection<T>.powerset(): Set<Set<T>> = powerset(this, setOf(setOf()))

private tailrec fun <T> powerset(left: Collection<T>, acc: Set<Set<T>>): Set<Set<T>> = when {
    left.isEmpty() -> acc
    else ->powerset(left.drop(1), acc + acc.map { it + left.first() })
}

val <T> Collection<T>.powersetSize: Int
    get() = 2.pow(size)