/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.animation

import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.LocationIfc
import org.hipparchus.linear.EigenDecompositionSymmetric
import org.hipparchus.linear.MatrixUtils
import kotlin.math.sqrt

/**
 * Proposes 2-D coordinates for the named locations of this [DistancesModel] via classical (Torgerson)
 * multidimensional scaling (8K.6b) — so a coordinate-free distance model can be placed in a layout
 * without hand-picking coordinates that honor the distance matrix. The directed matrix is symmetrized and, for a
 * sparse model (one that defines only some pairs, e.g. a tandem line), completed by shortest paths; coordinates
 * are uniformly scaled (shape-preserving) and centered to fit a [width] × [height] box with [margin] padding.
 * MDS is rotation/reflection-invariant, so orientation is arbitrary.
 *
 * Uses Hipparchus for the eigendecomposition of the double-centered matrix.
 */
fun DistancesModel.proposeCoordinates(
    width: Double = 800.0,
    height: Double = 500.0,
    margin: Double = 60.0
): Map<String, LayoutPoint> {
    val locs = locations.toList()
    val n = locs.size
    if (n == 0) return emptyMap()
    if (n == 1) return mapOf(locs[0].name to LayoutPoint(width / 2.0, height / 2.0))

    // Symmetric distance matrix. A DistancesModel is often sparse (e.g. a tandem line defines only consecutive
    // hops), so an undefined pair reads as unknown instead of throwing; shortest paths (Floyd–Warshall) fill the
    // gaps and any still-disconnected pair takes a large finite default, so MDS always gets a complete matrix.
    fun oneWay(a: LocationIfc, b: LocationIfc): Double? = try { distance(a, b) } catch (_: IllegalArgumentException) { null }
    val dist = Array(n) { i ->
        DoubleArray(n) { j ->
            if (i == j) 0.0 else {
                val ab = oneWay(locs[i], locs[j]); val ba = oneWay(locs[j], locs[i])
                when {
                    ab != null && ba != null -> 0.5 * (ab + ba)
                    ab != null -> ab
                    ba != null -> ba
                    else -> Double.POSITIVE_INFINITY
                }
            }
        }
    }
    for (k in 0 until n) for (i in 0 until n) for (j in 0 until n) {
        val through = dist[i][k] + dist[k][j]
        if (through < dist[i][j]) dist[i][j] = through
    }
    val maxFinite = dist.flatMap { it.asList() }.filter { it.isFinite() }.maxOrNull() ?: 1.0
    for (i in 0 until n) for (j in 0 until n) if (!dist[i][j].isFinite()) dist[i][j] = maxFinite * 2.0
    // Squared distances for classical MDS.
    val d2 = Array(n) { i -> DoubleArray(n) { j -> dist[i][j] * dist[i][j] } }
    // Double-centering: B = -1/2 · J · D² · J, with J = I - (1/n)·11ᵀ.
    val rowMean = DoubleArray(n) { i -> d2[i].average() }
    val grand = rowMean.average()
    val b = Array(n) { i -> DoubleArray(n) { j -> -0.5 * (d2[i][j] - rowMean[i] - rowMean[j] + grand) } }

    val eig = EigenDecompositionSymmetric(MatrixUtils.createRealMatrix(b))
    val vals = eig.eigenvalues
    val order = vals.indices.sortedByDescending { vals[it] } // largest eigenvalues first
    fun axis(k: Int): DoubleArray {
        val idx = order.getOrNull(k) ?: return DoubleArray(n)
        val scale = sqrt(vals[idx].coerceAtLeast(0.0))
        val v = eig.getEigenvector(idx)
        return DoubleArray(n) { v.getEntry(it) * scale }
    }
    val xs = axis(0)
    val ys = axis(1)

    // Uniform (shape-preserving) scale + center into the box.
    val xMin = xs.min(); val xMax = xs.max(); val yMin = ys.min(); val yMax = ys.max()
    val xr = (xMax - xMin).coerceAtLeast(1e-9)
    val yr = (yMax - yMin).coerceAtLeast(1e-9)
    val scale = minOf((width - 2 * margin) / xr, (height - 2 * margin) / yr)
    val cx = (xMin + xMax) / 2.0; val cy = (yMin + yMax) / 2.0
    return locs.indices.associate {
        locs[it].name to LayoutPoint(width / 2.0 + (xs[it] - cx) * scale, height / 2.0 + (ys[it] - cy) * scale)
    }
}
