package org.jetbrains.exposed.sql

import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.jetbrains.exposed.sql.vendors.inProperCase
import java.util.*

object SchemaUtils {
    private class TableDepthGraph(val tables: List<Table>) {
        val graph = fetchAllTables().associate { t ->
            t to t.columns.mapNotNull { c ->
                c.referee?.let{ it.table to c.columnType.nullable }
            }.toMap()
        }

        private fun fetchAllTables(): HashSet<Table> {
            val result = HashSet<Table>()

            fun parseTable(table: Table) {
                if (result.add(table)) {
                    table.columns.forEach {
                        it.referee?.table?.let(::parseTable)
                    }
                }
            }
            tables.forEach(::parseTable)
            return result
        }

        fun sorted() : List<Table> {
            val visited = mutableSetOf<Table>()
            val result = arrayListOf<Table>()

            fun traverse(table: Table) {
                if (table !in visited) {
                    visited += table
                    graph[table]!!.forEach { t, u ->
                        if (t !in visited) {
                            traverse(t)
                        }
                    }
                    result += table
                }
            }

            tables.forEach(::traverse)
            return result
        }

        fun hasCycle() : Boolean {
            val visited = mutableSetOf<Table>()
            val recursion = mutableSetOf<Table>()

            val sortedTables = sorted()

            fun traverse(table: Table) : Boolean {
                if (table in recursion) return true
                if (table in visited) return false
                recursion += table
                visited += table
                return if (graph[table]!!.any{ traverse(it.key) }) {
                    true
                } else {
                    recursion -= table
                    false
                }
            }
            return sortedTables.any { traverse(it) }
        }
    }

    fun sortTablesByReferences(tables: Iterable<Table>) = TableDepthGraph(tables.toList()).sorted()
    fun checkCycle(vararg tables: Table) = TableDepthGraph(tables.toList()).hasCycle()

    fun createStatements(vararg tables: Table): List<String> {
        if (tables.isEmpty())
            return emptyList()

        val toCreate = sortTablesByReferences(tables.toList()).filterNot { it.exists() }
        val alters = arrayListOf<String>()
        return toCreate.flatMap { table ->
            val (create, alter) = table.ddl.partition { it.startsWith("CREATE ") }
            val indicesDDL = table.indices.flatMap { createIndex(it) }
            alters += alter
            create + indicesDDL
        } + alters
    }

    fun createSequence(name: String) = Seq(name).createStatement()

    fun createFKey(reference: Column<*>) = ForeignKeyConstraint.from(reference).createStatement()

    @Deprecated(message = "pass instance of Index", replaceWith = ReplaceWith("SchemaUtils.createIndex(index)"))
    fun createIndex(columns: Array<out Column<*>>, isUnique: Boolean) = createIndex(Index(columns.toList(), unique = isUnique))

    fun createIndex(index: Index) = index.createStatement()

    private fun addMissingColumnsStatements(vararg tables: Table): List<String> {
        with(TransactionManager.current()) {
            val statements = ArrayList<String>()
            if (tables.isEmpty())
                return statements

            val existingTableColumns = logTimeSpent("Extracting table columns") {
                currentDialect.tableColumns(*tables)
            }

            for (table in tables) {
                //create columns
                val thisTableExistingColumns = existingTableColumns[table].orEmpty()
                val missingTableColumns = table.columns.filterNot { c -> thisTableExistingColumns.any { it.name.equals(c.name, true) } }
                missingTableColumns.flatMapTo(statements) { it.ddl }

                if (db.supportsAlterTableWithAddColumn) {
                    // create indexes with new columns
                    for (index in table.indices) {
                        if (index.columns.any { missingTableColumns.contains(it) }) {
                            statements.addAll(createIndex(index))
                        }
                    }

                    // sync nullability of existing columns
                    val incorrectNullabilityColumns = table.columns.filter { c ->
                        thisTableExistingColumns.any { c.name.equals(it.name, true) && it.nullable != c.columnType.nullable }
                    }
                    incorrectNullabilityColumns.flatMapTo(statements) { it.modifyStatement() }
                }
            }

            if (db.supportsAlterTableWithAddColumn) {
                val existingColumnConstraint = logTimeSpent("Extracting column constraints") {
                    db.dialect.columnConstraints(*tables)
                }

                for (table in tables) {
                    for (column in table.columns) {
                        if (column.referee != null) {
                            val existingConstraint = existingColumnConstraint[table.tableName.inProperCase() to column.name.inProperCase()]?.firstOrNull()
                            if (existingConstraint == null) {
                                statements.addAll(createFKey(column))
                            } else if (existingConstraint.referencedTable != column.referee!!.table.tableName.inProperCase()
                                    || column.onDelete != existingConstraint.deleteRule
                                    || column.onUpdate != existingConstraint.updateRule) {
                                statements.addAll(existingConstraint.dropStatement())
                                statements.addAll(createFKey(column))
                            }
                        }
                    }
                }
            }

            return statements
        }

    }

    fun <T : Table> create(vararg tables: T) {
        with(TransactionManager.current()) {
            val statements = createStatements(*tables)
            for (statement in statements) {
                exec(statement)
            }
            commit()
            currentDialect.resetCaches()
        }
    }

    /**
     * This function should be used in cases when you want an easy-to-use auto-actualization of database scheme.
     * It will create all absent tables, add missing columns for existing tables if it's possible (columns are nullable or have default values).
     *
     * Also if there is inconsistency in DB vs code mappings (excessive or absent indexes)
     * then DDLs to fix it will be logged to exposedLogger.
     *
     * This functionality is based on jdbc metadata what might be a bit slow, so it is recommended to call this function once
     * at application startup and provide all tables you want to actualize.
     *
     * Please note, that execution of this function concurrently might lead to unpredictable state in database due to
     * non-transactional behavior of some DBMS on processing DDL statements (e.g. MySQL) and metadata caches.

     * To prevent such cases is advised to use any "global" synchronization you prefer (via redis, memcached, etc) or
     * with Exposed's provided lock based on synchronization on a dummy "Buzy" table (@see SchemaUtils#withDataBaseLock).
     */
    fun createMissingTablesAndColumns(vararg tables: Table) {
        with(TransactionManager.current()) {
            db.dialect.resetCaches()
            val createStatements = logTimeSpent("Preparing create tables statements") {
                createStatements(*tables)
            }
            logTimeSpent("Executing create tables statements") {
                for (statement in createStatements) {
                    exec(statement)
                }
                commit()
            }

            val alterStatements = logTimeSpent("Preparing alter table statements") {
                addMissingColumnsStatements(*tables)
            }
            logTimeSpent("Executing alter table statements") {
                for (statement in alterStatements) {
                    exec(statement)
                }
                commit()
            }
            logTimeSpent("Checking mapping consistence") {
                for (statement in checkMappingConsistence(*tables).filter { it !in statements }) {
                    exec(statement)
                }
                commit()
            }
            db.dialect.resetCaches()
        }
    }


    /**
     * Creates table with name "busy" (if not present) and single column to be used as "synchronization" point. Table wont be dropped after execution.
     *
     * All code provided in _body_ closure will be executed only if there is no another code which running under "withDataBaseLock" at same time.
     * That means that concurrent execution of long running tasks under "database lock" might lead to that only first of them will be really executed.
     */
    fun <T> Transaction.withDataBaseLock(body: () -> T) {
        val buzyTable = object : Table("busy") {
            val busy = bool("busy").uniqueIndex()
        }
        create(buzyTable)
        val isBusy = buzyTable.selectAll().forUpdate().any()
        if (!isBusy) {
            buzyTable.insert { it[buzyTable.busy] = true }
            try {
                body()
            } finally {
                buzyTable.deleteAll()
                connection.commit()
            }
        }
    }

    fun drop(vararg tables: Table) {
        if (tables.isEmpty()) return
        val transaction = TransactionManager.current()
        transaction.flushCache()
        var tablesForDeletion = SchemaUtils
                .sortTablesByReferences(tables.toList())
                .reversed()
                .filter { it in tables }
        if (!currentDialect.supportsIfNotExists) {
            tablesForDeletion = tablesForDeletion.filter { it.exists()}
        }
        tablesForDeletion
                .flatMap { it.dropStatement() }
                .forEach {
                    transaction.exec(it)
                }
        currentDialect.resetCaches()
    }
}
