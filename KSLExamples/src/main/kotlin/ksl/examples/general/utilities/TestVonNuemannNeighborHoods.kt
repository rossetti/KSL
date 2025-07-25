package ksl.examples.general.utilities

import ksl.utilities.manhattanDistance

fun vonNeumannNeighborhoodStringVersion(
    center: IntArray,
    radius: Int,
    includeCenter: Boolean = true
): List<IntArray> {
    val result = mutableListOf<IntArray>()
    val dimensions = center.size

    // Generate all points within Manhattan distance <= radius
    val queue = mutableListOf<IntArray>()
    val visited = mutableSetOf<String>()

    // Start with the center point
    queue.add(center.copyOf())
    visited.add(center.joinToString(","))

    var queueIndex = 0
    while (queueIndex < queue.size) {
        val current = queue[queueIndex]
        queueIndex++

        // Calculate Manhattan distance from center
        var manhattanDistance = 0
        for (i in 0 until dimensions) {
            manhattanDistance += kotlin.math.abs(current[i] - center[i])
        }

        // Add to result if within radius and meets center inclusion criteria
        if (manhattanDistance <= radius) {
            val isCenter = current.contentEquals(center)
            if (!isCenter || includeCenter) {
                result.add(current.copyOf())
            }
        }

        // Add neighbors if we haven't reached the radius limit
        if (manhattanDistance < radius) {
            for (dim in 0 until dimensions) {
                // Add neighbor in positive direction
                val posNeighbor = current.copyOf()
                posNeighbor[dim]++
                val posKey = posNeighbor.joinToString(",")
                if (!visited.contains(posKey)) {
                    visited.add(posKey)
                    queue.add(posNeighbor)
                }

                // Add neighbor in negative direction
                val negNeighbor = current.copyOf()
                negNeighbor[dim]--
                val negKey = negNeighbor.joinToString(",")
                if (!visited.contains(negKey)) {
                    visited.add(negKey)
                    queue.add(negNeighbor)
                }
            }
        }
    }

    return result
}

fun vonNeumannNeighborhood(
    center: IntArray,
    radius: Int,
    includeCenter: Boolean = true
): List<IntArray> {
    val result = mutableListOf<IntArray>()
    val dimensions = center.size

    // Generate all points within Manhattan distance <= radius
    val queue = mutableListOf<IntArray>()
    val visited = mutableSetOf<Point>()

    // Start with the center point
    queue.add(center.copyOf())
    visited.add(Point(center.copyOf()))

    var queueIndex = 0
    while (queueIndex < queue.size) {
        val current = queue[queueIndex]
        queueIndex++

        // Calculate Manhattan distance from center
        val manhattanDistance = center.manhattanDistance(current)
//        for (i in 0 until dimensions) {
//            manhattanDistance += kotlin.math.abs(current[i] - center[i])
//        }

        // Add to result if within radius and meets center inclusion criteria
        if (manhattanDistance <= radius) {
            val isCenter = current.contentEquals(center)
            if (!isCenter || includeCenter) {
                result.add(current.copyOf())
            }
        }

        // Add neighbors if we haven't reached the radius limit
        if (manhattanDistance < radius) {
            for (dim in 0 until dimensions) {
                // Add neighbor in positive direction
                val posNeighbor = current.copyOf()
                posNeighbor[dim]++
                val posPoint = Point(posNeighbor.copyOf())
                if (!visited.contains(posPoint)) {
                    visited.add(posPoint)
                    queue.add(posNeighbor)
                }

                // Add neighbor in negative direction
                val negNeighbor = current.copyOf()
                negNeighbor[dim]--
                val negPoint = Point(negNeighbor.copyOf())
                if (!visited.contains(negPoint)) {
                    visited.add(negPoint)
                    queue.add(negNeighbor)
                }
            }
        }
    }

    return result
}

// Alternative implementation using nested loops (more direct approach)
fun vonNeumannNeighborhoodDirect(
    center: IntArray,
    radius: Int,
    includeCenter: Boolean = true
): List<IntArray> {
    val result = mutableListOf<IntArray>()
    val dimensions = center.size

    // Create bounds for each dimension
    val minBounds = IntArray(dimensions)
    val maxBounds = IntArray(dimensions)

    for (i in 0 until dimensions) {
        minBounds[i] = center[i] - radius
        maxBounds[i] = center[i] + radius
    }

    // Generate all combinations within bounds
    val indices = IntArray(dimensions)
    for (i in 0 until dimensions) {
        indices[i] = minBounds[i]
    }

    var finished = false
    while (!finished) {
        // Calculate Manhattan distance
        val manhattanDistance = center.manhattanDistance(indices)
//        for (i in 0 until dimensions) {
//            manhattanDistance += kotlin.math.abs(indices[i] - center[i])
//        }

        // Add point if within von Neumann neighborhood
        if (manhattanDistance <= radius) {
            val isCenter = indices.contentEquals(center)
            if (!isCenter || includeCenter) {
                result.add(indices.copyOf())
            }
        }

        // Increment indices (like an odometer)
        var carry = 1
        for (i in 0 until dimensions) {
            if (carry == 0) break

            indices[i] += carry
            if (indices[i] <= maxBounds[i]) {
                carry = 0
            } else {
                indices[i] = minBounds[i]
                carry = 1
            }
        }

        if (carry == 1) {
            finished = true
        }
    }

    return result
}

// Example usage and test function
fun main() {
    // Test 2D case
    val center2D = intArrayOf(0, 0)
    println("2D von Neumann neighborhood (radius 2, include center):")
    val result2D = vonNeumannNeighborhood(center2D, 2, true)
    for (point in result2D) {
        println("[${point.joinToString(", ")}]")
    }

    println("\n2D von Neumann neighborhood (radius 1, exclude center):")
    val result2DNoCenter = vonNeumannNeighborhood(center2D, 1, false)
    for (point in result2DNoCenter) {
        println("[${point.joinToString(", ")}]")
    }

    // Test 3D case
    println("\n3D von Neumann neighborhood (radius 1, include center):")
    val center3D = intArrayOf(5, 5, 5)
    val result3D = vonNeumannNeighborhood(center3D, 1, true)
    for (point in result3D) {
        println("[${point.joinToString(", ")}]")
    }
}