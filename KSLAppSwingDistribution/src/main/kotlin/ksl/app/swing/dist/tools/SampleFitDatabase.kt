/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.dist.tools

import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.SQLiteDb
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.WeibullRV
import java.nio.file.Path

/**
 * Generates a sample SQLite database for manually exercising the Database import
 * card. The samples come from known families with fixed streams, so a fit should
 * recover the family that produced each column/group — handy for verification.
 */
object SampleFitDatabase {

    /**
     * Creates (overwriting any existing) a sample SQLite database at [dir]/[name]
     * with a WIDE table `service_times` (one numeric column per family plus a text
     * column) and a LONG table `observations` (`family`, `value`). Returns the path.
     */
    fun generate(dir: Path = KSL.outDir, name: String = "sample_fit.db", n: Int = 200): Path {
        val db = SQLiteDb(name, dir, deleteIfExists = true)

        val exponential = ExponentialRV(mean = 10.0, streamNum = 1)
        val gamma = GammaRV(shape = 2.0, scale = 5.0, streamNum = 2)
        val weibull = WeibullRV(shape = 1.5, scale = 8.0, streamNum = 3)
        val lognormal = LognormalRV(mean = 10.0, variance = 25.0, streamNum = 4)
        val normal = NormalRV(mean = 20.0, variance = 9.0, streamNum = 5)
        db.executeCommand(
            "CREATE TABLE service_times " +
                "(exponential REAL, gamma REAL, weibull REAL, lognormal REAL, normal REAL, note TEXT)"
        )
        db.executeCommands((1..n).map { i ->
            "INSERT INTO service_times VALUES (${exponential.value}, ${gamma.value}, " +
                "${weibull.value}, ${lognormal.value}, ${normal.value}, 'row$i')"
        })

        val obsExponential = ExponentialRV(mean = 4.0, streamNum = 6)
        val obsNormal = NormalRV(mean = 50.0, variance = 16.0, streamNum = 7)
        db.executeCommand("CREATE TABLE observations (family TEXT, value REAL)")
        db.executeCommands((1..n).flatMap {
            listOf(
                "INSERT INTO observations VALUES ('exp', ${obsExponential.value})",
                "INSERT INTO observations VALUES ('normal', ${obsNormal.value})"
            )
        })

        return dir.resolve(name)
    }
}

fun main() {
    val path = SampleFitDatabase.generate()
    println("Wrote sample database to: ${path.toAbsolutePath()}")
    println("  WIDE table 'service_times': exponential, gamma, weibull, lognormal, normal (+ note TEXT)")
    println("  LONG table 'observations': family, value")
}
