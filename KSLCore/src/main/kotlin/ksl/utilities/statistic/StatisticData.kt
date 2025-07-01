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

package ksl.utilities.statistic

import ksl.utilities.io.dbutil.DbTableData
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.remove
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

data class StatisticData (
    val name: String,
    val count: Double,
    val average: Double,
    val standardDeviation: Double,
    val standardError: Double,
    val halfWidth: Double,
    val confidenceLevel: Double,
    val lowerLimit: Double,
    val upperLimit: Double,
    val min: Double,
    val max: Double,
    val sum: Double,
    val variance: Double,
    val deviationSumOfSquares: Double,
    val kurtosis: Double,
    val skewness: Double,
    val lag1Covariance: Double,
    val lag1Correlation: Double,
    val vonNeumannLag1TestStatistic: Double,
    val numberMissing: Double
) : Comparable<StatisticData> {

    /**
     * Returns a negative integer, zero, or a positive integer if this object is
     * less than, equal to, or greater than the specified object.
     *
     * The natural ordering is based on the average
     *
     * @param other The statistic to compare this statistic to
     * @return Returns a negative integer, zero, or a positive integer if this
     * object is less than, equal to, or greater than the specified object based on the average
     */
    override operator fun compareTo(other: StatisticData): Int {
        return average.compareTo(other.average)
    }
}

data class StatisticDataDb(
    var id: Int = statDataCounter++,
    var context: String? = null,
    var subject: String? = null,
    var stat_name: String = "",
    var stat_count: Double? = null,
    var average: Double? = null,
    var std_dev: Double? = null,
    var std_err: Double? = null,
    var half_width: Double? = null,
    var conf_level: Double? = null,
    var minimum: Double? = null,
    var maximum: Double? = null,
    var sum_of_obs: Double? = null,
    var dev_ssq: Double? = null,
    var last_value: Double? = null,
    var kurtosis: Double? = null,
    var skewness: Double? = null,
    var lag1_cov: Double? = null,
    var lag1_corr: Double? = null,
    var von_neumann_lag1_stat: Double? = null,
    var num_missing_obs: Double? = null
) : DbTableData("tblStatistic", keyFields = listOf("id"), autoIncField = false) {

    companion object {
        var statDataCounter : Int = 0
    }
}

/**
 *  Converts the statistic data to a data frame
 */
fun List<StatisticDataDb>.asStatisticDataFrame(): DataFrame<StatisticDataDb> {
    var df = this.toDataFrame()
    df = df.remove("autoIncField", "keyFields",
        "numColumns", "numInsertFields", "numUpdateFields", "schemaName", "tableName")
    return df
}
