package ksl.utilities.dbutil

import java.sql.Connection
import java.sql.DatabaseMetaData
import javax.sql.DataSource

open class DatabaseImp(
    final override val dataSource: DataSource,
    final override val label: String,
    final override var defaultSchemaName: String? = null
) : DatabaseIfc {

    final override val connection: Connection
        get() = super.connection

    override val dbURL: String? = connection.metaData?.url

    init{
        DatabaseIfc.logger.info{"Constructed DatabaseImp $label via $dbURL"}
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Database: $label")
        sb.appendLine("The database was connected via url $dbURL")
        sb.appendLine("The database has the following schemas:")
        sb.append("\t")
        sb.append(schemas.toString())
        sb.appendLine()
        sb.appendLine("The default schema is $defaultSchemaName")
        sb.appendLine("The database has the following user defined tables:")
        sb.append("\t")
        sb.append(userDefinedTables.toString())
        sb.appendLine()
        sb.appendLine("The database has the following views:")
        sb.append("\t")
        sb.append(views.toString())
        sb.appendLine()
        return sb.toString()
    }


}