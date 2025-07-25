package ksl.simopt.solvers

import ksl.simopt.problem.InputMap
import ksl.utilities.chebyshevDistance
import ksl.utilities.manhattanDistance

data class Point(val coordinates: IntArray) {

    @Suppress("unused")
    constructor(dimension: Int) : this(IntArray(dimension))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Point) return false
        return coordinates.contentEquals(other.coordinates)
    }

    override fun hashCode(): Int {
        return coordinates.contentHashCode()
    }
}

/**
 *  Defines a search neighborhood for the provided input
 *  with respect to the problem.
 *  The solver is supplied to allow potential access to its state/memory
 *  within the process to determine the neighborhood.
 */
fun interface NeighborhoodFinderIfc {

    /**
     *  Defines a search neighborhood for the provided input
     *  with respect to the problem. The function should guarantee that
     *  the returned set is not empty.
     *
     *  @param inputMap the location of the current point in the search space
     *  relative to which the neighborhood should be formed
     *  @param solver the solver needing the neighborhood
     *  @return a set of input points that form a search neighborhood around
     *  the provided point.
     */
    @Suppress("unused")
    fun neighborhood(
        inputMap: InputMap,
        solver: Solver?
    ): MutableSet<InputMap>

    companion object {

        /**
         *  A von Neumann neighborhood includes all points where the Manhattan distance (sum of absolute differences
         *  in all dimensions) from the center is less than or equal to the radius.  This function uses
         *  a direct iteration around a bounding box.  This function returns the von Neumann neighborhood
         *  around an array of zeroes of size [dimension]
         *
         *  @param dimension size of the zero-based center point
         *  @param radius the radius for the neighborhood. The default is 1.
         *  @param includeCenter indicates if the center point should be included in the returned array
         *  @return the points in the neighborhood
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("unused")
        fun zeroVonNeumannNeighborhood(
            dimension: Int,
            radius: Int = 1,
            includeCenter: Boolean = false
        ) : List<IntArray> {
            return vonNeumannNeighborhood(IntArray(dimension), radius, includeCenter)
        }

        /**
         *  A von Neumann neighborhood includes all points where the Manhattan distance (sum of absolute differences
         *  in all dimensions) from the center is less than or equal to the radius. This function uses
         *  a direct iteration around a bounding box.
         *
         *  @param center the point around which the neighborhood resides
         *  @param radius the radius for the neighborhood. The default is 1.
         *  @param includeCenter indicates if the center point should be included in the returned array
         *  @return the points in the neighborhood
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("unused")
        fun vonNeumannNeighborhood(
            center: IntArray,
            radius: Int = 1,
            includeCenter: Boolean = false
        ): List<IntArray> {
            require(center.isNotEmpty()) {"The dimension must be 1 or more"}
            require( radius >= 0 ) {"The radius must be greater than or equal to 0"}
            if (radius == 0){
                return if (includeCenter){
                    listOf(center.copyOf())
                } else {
                    listOf()
                }
            }
            val dimensions = center.size
            require(dimensions >= 1) {"The dimension must be 1 or more"}
            val result = mutableListOf<IntArray>()
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

        /**
         *  A von Neumann neighborhood includes all points where the Manhattan distance (sum of absolute differences
         *  in all dimensions) from the center is less than or equal to the radius. This function uses
         *  an outward expanding approach from the center point using breadth-first search (BFS).
         *
         *  @param center the point around which the neighborhood resides
         *  @param radius the radius for the neighborhood. The default is 1.
         *  @param includeCenter indicates if the center point should be included in the returned array
         *  @return the points in the neighborhood
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("unused")
        fun vonNeumannNeighborhoodBFS(
            center: IntArray,
            radius: Int = 1,
            includeCenter: Boolean = false
        ): List<IntArray> {
            require(center.isNotEmpty()) {"The dimension must be 1 or more"}
            require( radius >= 0 ) {"The radius must be greater than or equal to 0"}
            if (radius == 0){
                return if (includeCenter){
                    listOf(center.copyOf())
                } else {
                    listOf()
                }
            }
            val dimensions = center.size
            val result = mutableListOf<IntArray>()

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

        /**
         *  A Moore neighborhood includes all points where the Chebyshev distance (also called L∞ distance)
         *  from the center is less than or equal to the radius. The Chebyshev distance is the maximum
         *  absolute difference across all dimensions. This function uses
         *  a direct iteration around a bounding box.  This function returns the Moore neighborhood
         *  around an array of zeroes of size [dimension]
         *
         *  @param dimension size of the zero-based center point
         *  @param radius the radius for the neighborhood. The default is 1.
         *  @param includeCenter indicates if the center point should be included in the returned array
         *  @return the points in the neighborhood
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("unused")
        fun zeroMooreNeighborhood(
            dimension: Int,
            radius: Int = 1,
            includeCenter: Boolean = false
        ) : List<IntArray> {
            return mooreNeighborhood(IntArray(dimension), radius, includeCenter)
        }

        /**
         *  A Moore neighborhood includes all points where the Chebyshev distance (also called L∞ distance)
         *  from the center is less than or equal to the radius. The Chebyshev distance is the maximum
         *  absolute difference across all dimensions. This function uses
         *  a direct iteration around a bounding box.
         *
         *  @param center the point around which the neighborhood resides
         *  @param radius the radius for the neighborhood. The default is 1.
         *  @param includeCenter indicates if the center point should be included in the returned array
         *  @return the points in the neighborhood
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("unused")
        fun mooreNeighborhood(
            center: IntArray,
            radius: Int = 1,
            includeCenter: Boolean = false
        ): List<IntArray> {
            require(center.isNotEmpty()) {"The dimension must be 1 or more"}
            require( radius >= 0 ) {"The radius must be greater than or equal to 0"}
            if (radius == 0){
                return if (includeCenter){
                    listOf(center.copyOf())
                } else {
                    listOf()
                }
            }
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
                val chebyshevDistance = center.chebyshevDistance(indices)

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

        /**
         *  A Moore neighborhood includes all points where the Chebyshev distance (also called L∞ distance)
         *  from the center is less than or equal to the radius. The Chebyshev distance is the maximum
         *  absolute difference across all dimensions. This function expands from the center outward
         *  using a breadth-first search to find the elements.
         *
         *  @param center the point around which the neighborhood resides
         *  @param radius the radius for the neighborhood. The default is 1.
         *  @param includeCenter indicates if the center point should be included in the returned array
         *  @return the points in the neighborhood
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("unused")
        fun mooreNeighborhoodBFS(
            center: IntArray,
            radius: Int = 1,
            includeCenter: Boolean = false
        ): List<IntArray> {
            require(center.isNotEmpty()) {"The dimension must be 1 or more"}
            require( radius >= 0 ) {"The radius must be greater than or equal to 0"}
            if (radius == 0){
                return if (includeCenter){
                    listOf(center.copyOf())
                } else {
                    listOf()
                }
            }
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
                val chebyshevDistance = center.chebyshevDistance(current)

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

                        // Only add if the direction has at least one non-zero component
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
    }
}