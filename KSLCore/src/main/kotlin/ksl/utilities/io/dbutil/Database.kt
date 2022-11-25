package ksl.utilities.io.dbutil

//import org.ktorm.database.Database
//import org.ktorm.logging.Slf4jLoggerAdapter
import java.sql.Connection
import javax.sql.DataSource

open class Database(
    final override val dataSource: DataSource,
    final override val label: String,
    final override var defaultSchemaName: String? = null
) : DatabaseIfc {

    final override fun getConnection(): Connection = super.getConnection()

    override val dbURL: String? = getConnection().metaData?.url

    init {
        DatabaseIfc.logger.info { "Constructed DatabaseImp $label via $dbURL" }
    }

//    val db = Database.connect(dataSource, logger = Slf4jLoggerAdapter(DatabaseIfc.logger))

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