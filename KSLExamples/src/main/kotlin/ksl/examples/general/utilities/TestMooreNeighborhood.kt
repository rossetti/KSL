package ksl.examples.general.utilities

fun mooreNeighborhood(
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

    // Generate all combinations within bounds using iterative approach
    val indices = IntArray(dimensions)
    for (i in 0 until dimensions) {
        indices[i] = minBounds[i]
    }

    var finished = false
    while (!finished) {
        // Check if current point is within Moore neighborhood (Chebyshev distance <= radius)
        var chebyshevDistance = 0
        for (i in 0 until dimensions) {
            val distance = kotlin.math.abs(indices[i] - center[i])
            if (distance > chebyshevDistance) {
                chebyshevDistance = distance
            }
        }

        // Add point if within Moore neighborhood
        if (chebyshevDistance <= radius) {
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

// Alternative implementation using queue-based exploration
fun mooreNeighborhoodBFSStringVersion(
    center: IntArray,
    radius: Int,
    includeCenter: Boolean = true
): List<IntArray> {
    val result = mutableListOf<IntArray>()
    val dimensions = center.size
    val visited = mutableSetOf<String>()
    val queue = mutableListOf<IntArray>()

    // Start with center point
    queue.add(center.copyOf())
    visited.add(center.joinToString(","))

    var queueIndex = 0
    while (queueIndex < queue.size) {
        val current = queue[queueIndex]
        queueIndex++

        // Calculate Chebyshev distance (max distance in any dimension)
        var chebyshevDistance = 0
        for (i in 0 until dimensions) {
            val distance = kotlin.math.abs(current[i] - center[i])
            if (distance > chebyshevDistance) {
                chebyshevDistance = distance
            }
        }

        // Add to result if within radius
        if (chebyshevDistance <= radius) {
            val isCenter = current.contentEquals(center)
            if (!isCenter || includeCenter) {
                result.add(current.copyOf())
            }
        }

        // Add all neighbors if we haven't reached the radius limit
        if (chebyshevDistance < radius) {
            // Generate all possible neighbor directions
            val directions = mutableListOf<IntArray>()

            // Create all combinations of -1, 0, +1 for each dimension
            val directionIndices = IntArray(dimensions) { 0 } // Start with all 0s
            val directionValues = intArrayOf(-1, 0, 1)

            var directionFinished = false
            while (!directionFinished) {
                // Create direction vector
                val direction = IntArray(dimensions)
                var hasNonZero = false
                for (i in 0 until dimensions) {
                    direction[i] = directionValues[directionIndices[i]]
                    if (direction[i] != 0) {
                        hasNonZero = true
                    }
                }

                // Only add if direction has at least one non-zero component
                if (hasNonZero) {
                    directions.add(direction)
                }

                // Increment direction indices
                var directionCarry = 1
                for (i in 0 until dimensions) {
                    if (directionCarry == 0) break

                    directionIndices[i] += directionCarry
                    if (directionIndices[i] < directionValues.size) {
                        directionCarry = 0
                    } else {
                        directionIndices[i] = 0
                        directionCarry = 1
                    }
                }

                if (directionCarry == 1) {
                    directionFinished = true
                }
            }

            // Apply each direction to current point
            for (direction in directions) {
                val neighbor = IntArray(dimensions)
                for (i in 0 until dimensions) {
                    neighbor[i] = current[i] + direction[i]
                }

                val neighborKey = neighbor.joinToString(",")
                if (!visited.contains(neighborKey)) {
                    visited.add(neighborKey)
                    queue.add(neighbor)
                }
            }
        }
    }

    return result
}

// Data class for proper array comparison in sets
data class Point(val coordinates: IntArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Point) return false
        return coordinates.contentEquals(other.coordinates)
    }

    override fun hashCode(): Int {
        return coordinates.contentHashCode()
    }
}

// Alternative implementation using queue-based exploration
fun mooreNeighborhoodBFS(
    center: IntArray,
    radius: Int,
    includeCenter: Boolean = true
): List<IntArray> {
    val result = mutableListOf<IntArray>()
    val dimensions = center.size
    val visited = mutableSetOf<Point>()
    val queue = mutableListOf<IntArray>()

    // Start with center point
    queue.add(center.copyOf())
    visited.add(Point(center.copyOf()))

    var queueIndex = 0
    while (queueIndex < queue.size) {
        val current = queue[queueIndex]
        queueIndex++

        // Calculate Chebyshev distance (max distance in any dimension)
        var chebyshevDistance = 0
        for (i in 0 until dimensions) {
            val distance = kotlin.math.abs(current[i] - center[i])
            if (distance > chebyshevDistance) {
                chebyshevDistance = distance
            }
        }

        // Add to result if within radius
        if (chebyshevDistance <= radius) {
            val isCenter = current.contentEquals(center)
            if (!isCenter || includeCenter) {
                result.add(current.copyOf())
            }
        }

        // Add all neighbors if we haven't reached the radius limit
        if (chebyshevDistance < radius) {
            // Generate all possible neighbor directions
            val directions = mutableListOf<IntArray>()

            // Create all combinations of -1, 0, +1 for each dimension
            val directionIndices = IntArray(dimensions) { 0 } // Start with all 0s
            val directionValues = intArrayOf(-1, 0, 1)

            var directionFinished = false
            while (!directionFinished) {
                // Create direction vector
                val direction = IntArray(dimensions)
                var hasNonZero = false
                for (i in 0 until dimensions) {
                    direction[i] = directionValues[directionIndices[i]]
                    if (direction[i] != 0) {
                        hasNonZero = true
                    }
                }

                // Only add if direction has at least one non-zero component
                if (hasNonZero) {
                    directions.add(direction)
                }

                // Increment direction indices
                var directionCarry = 1
                for (i in 0 until dimensions) {
                    if (directionCarry == 0) break

                    directionIndices[i] += directionCarry
                    if (directionIndices[i] < directionValues.size) {
                        directionCarry = 0
                    } else {
                        directionIndices[i] = 0
                        directionCarry = 1
                    }
                }

                if (directionCarry == 1) {
                    directionFinished = true
                }
            }

            // Apply each direction to current point
            for (direction in directions) {
                val neighbor = IntArray(dimensions)
                for (i in 0 until dimensions) {
                    neighbor[i] = current[i] + direction[i]
                }

                val neighborPoint = Point(neighbor.copyOf())
                if (!visited.contains(neighborPoint)) {
                    visited.add(neighborPoint)
                    queue.add(neighbor)
                }
            }
        }
    }

    return result
}

// Example usage and test function
fun main() {
    // Test 2D case
    val center2D = intArrayOf(0, 0)
    println("2D Moore neighborhood (radius 2, include center):")
    val result2D = mooreNeighborhoodBFS(center2D, 2, true)
    for (point in result2D) {
        println("[${point.joinToString(", ")}]")
    }

    println("\n2D Moore neighborhood (radius 1, exclude center):")
    val result2DNoCenter = mooreNeighborhoodBFS(center2D, 1, false)
    for (point in result2DNoCenter) {
        println("[${point.joinToString(", ")}]")
    }

    // Test 3D case
    println("\n3D Moore neighborhood (radius 1, include center):")
    val center3D = intArrayOf(5, 5, 5)
    val result3D = mooreNeighborhoodBFS(center3D, 1, true)
    for (point in result3D) {
        println("[${point.joinToString(", ")}]")
    }

    // Compare sizes
//    println("\nComparison for 2D, radius 1:")
//    println("Moore neighborhood size: ${mooreNeighborhood(center2D, 1, true).size}")
//    println("Moore neighborhood (no center): ${mooreNeighborhood(center2D, 1, false).size}")
}