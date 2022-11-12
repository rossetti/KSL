package ksl.utilities.dbutil

import javax.sql.DataSource

open class DatabaseImp(
    override val dataSource: DataSource,
    override val label: String,
    override var defaultSchemaName: String? = null
) : DatabaseIfc {

}