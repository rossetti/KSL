//@file:JvmName("Sets")

package ksl.utilities.maps

object Sets {

    /**
     * This function calculates the power set iteratively. It starts with an empty set in the power set.
     * Then, for each element in the original set, it adds the element to each existing subset in the power set,
     * creating new subsets, and adds these new subsets to the power set.
     *
     * val originalSet = setOf(1, 2, 3)
     * val result = powerSet(originalSet)
     * println(result)
     * // Expected output: [[], [1], [2], [1, 2], [3], [1, 3], [2, 3], [1, 2, 3]]
     */
    fun <T> powerSet(originalSet: Set<T>): Set<Set<T>> {
        val powerSet = mutableSetOf<Set<T>>()
        powerSet.add(emptySet())

        for (element in originalSet) {
            val newSubsets = powerSet.map { it + element }
            powerSet.addAll(newSubsets)
        }
        return powerSet
    }
}

/**
 * This function calculates the power set iteratively. It starts with an empty set in the power set.
 * Then, for each element in the original set, it adds the element to each existing subset in the power set,
 * creating new subsets, and adds these new subsets to the power set.
 *
 * val originalSet = setOf(1, 2, 3)
 * val result = powerSet(originalSet)
 * println(result)
 * // Expected output: [[], [1], [2], [1, 2], [3], [1, 3], [2, 3], [1, 2, 3]]
 */
fun <T> Set<T>.powerSet(): Set<Set<T>> {
    return Sets.powerSet(this)
}