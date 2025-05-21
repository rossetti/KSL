package ksl.utilities.collections

import ksl.utilities.io.dbutil.DbTableData
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.remove
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

/**
 *  @param id the identifier for the observation
 *  @param context a context for interpreting the data.
 *  @param subject the subject (or name) of the observation
 *  @param response the name of the response being observed
 *  @param obsNum an ordered numbering of the observations within the subject
 *  @param obsValue the value of the observation
 */
data class ObservationDataDb(
    var id: Int = obsCounter++,
    var context: String? = null,
    var subject: String? = null,
    var response: String = "",
    var obsNum: Int = -1,
    var obsValue: Double = Double.NaN
) : DbTableData("tblObservations", keyFields = listOf("id"), autoIncField = false) {

    companion object {
        var obsCounter = 0
    }
}

/**
 *  Converts the data map to a long format view of the observations.
 *  @param tableName can be used to assign the data to a table name if using a database
 *  @param context an optional field to provide context for the data and use it to
 *  associate with other data.
 */
fun Map<String, DoubleArray>.toObservationData(
    tableName: String = "tblObservations",
    context: String? = null,
    subject: String? = null
): List<ObservationDataDb> {
    val list = mutableListOf<ObservationDataDb>()
    for ((s, array) in this) {
        for ((i, v) in array.withIndex()) {
            val data = ObservationDataDb(
                context = context,
                subject = subject,
                response = s,
                obsNum = i + 1,
                obsValue = v
            )
            data.tableName = tableName
            list.add(data)
        }
    }
    return list
}

/**
 *  Converts the observation data to a data frame
 */
fun List<ObservationDataDb>.asObservationDataFrame() : DataFrame<ObservationDataDb> {
    var df = this.toDataFrame()
    df = df.remove("autoIncField", "keyFields",
        "numColumns", "numInsertFields", "numUpdateFields", "schemaName", "tableName")
    return df
}