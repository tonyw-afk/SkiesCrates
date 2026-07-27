package com.pokeskies.skiescrates.storage.database.sql

import java.sql.Connection
import java.sql.SQLException

class SchemaMigrator(
    private val tables: SQLStorage.TableNames,
    private val useMySqlLock: Boolean
) {
    // these are hardcoded Migration instances for changes to the database schemas
    private val migrations = listOf(
        Migration(1, "Create initial tables") { createTables(it, false) },
        Migration(2, "Add user data version") { addUserDataVersion(it) }
    )

    @Throws(SQLException::class)
    fun migrate(connection: Connection) {
        if (useMySqlLock) {
            withLock(connection) { migrateSchema(connection) }
        } else {
            migrateSchema(connection)
        }
    }

    private fun migrateSchema(connection: Connection) {
        val hasMigrationHistory = hasTable(connection, tables.migrations)
        val hasExistingSchema = hasTable(connection, tables.userdata) ||
            hasTable(connection, tables.usedKeys)

        createMigrationTable(connection)

        if (!hasMigrationHistory && !hasExistingSchema) {
            createTables(connection, true)
            migrations.forEach { recordMigration(connection, it) }
            return
        }

        if (!hasMigrationHistory) {
            createTables(connection, false)
            recordMigration(connection, migrations.first())
        }

        val appliedVersions = getAppliedVersions(connection)
        val latestVersion = migrations.maxOf { it.version }
        val unsupportedVersion = appliedVersions.firstOrNull { it > latestVersion }
        if (unsupportedVersion != null) {
            throw SQLException(
                "Database schema version $unsupportedVersion is newer than supported version $latestVersion"
            )
        }

        migrations
            .filterNot { it.version in appliedVersions }
            .sortedBy { it.version }
            .forEach { applyMigration(connection, it) }
    }

    private fun createMigrationTable(connection: Connection) {
        connection.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ${tables.migrations} (" +
                    "version INTEGER NOT NULL, " +
                    "description VARCHAR(255) NOT NULL, " +
                    "applied_at BIGINT NOT NULL, " +
                    "PRIMARY KEY (version)" +
                    ")"
            )
        }
    }

    private fun createTables(connection: Connection, latest: Boolean) {
        val versionColumn = if (latest) "version BIGINT NOT NULL DEFAULT 0, " else ""
        connection.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ${tables.userdata} (" +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "crates TEXT NOT NULL, " +
                    "`keys` TEXT NOT NULL, " +
                    versionColumn +
                    "PRIMARY KEY (uuid)" +
                    ")"
            )
            it.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ${tables.usedKeys} (" +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "keyId TEXT NOT NULL, " +
                    "timeUsed BIGINT NOT NULL, " +
                    "player VARCHAR(36) NOT NULL, " +
                    "PRIMARY KEY (uuid)" +
                    ")"
            )
        }
    }

    private fun addUserDataVersion(connection: Connection) {
        if (hasColumn(connection, tables.userdata, "version")) return

        try {
            connection.createStatement().use {
                it.executeUpdate(
                    "ALTER TABLE ${tables.userdata} ADD COLUMN version BIGINT NOT NULL DEFAULT 0"
                )
            }
        } catch (e: SQLException) {
            if (!hasColumn(connection, tables.userdata, "version")) throw e
        }
    }

    private fun applyMigration(connection: Connection, migration: Migration) {
        try {
            migration.apply(connection)
            recordMigration(connection, migration)
        } catch (e: Exception) {
            throw SQLException(
                "Failed to apply schema migration ${migration.version}: ${migration.description}",
                e
            )
        }
    }

    private fun withLock(connection: Connection, migrate: () -> Unit) {
        val lockName = "${connection.catalog ?: "database"}:${tables.migrations}".take(64)
        connection.prepareStatement("SELECT GET_LOCK(?, 30)").use {
            it.setString(1, lockName)
            it.executeQuery().use { result ->
                if (!result.next() || result.getInt(1) != 1) {
                    throw SQLException("Timed out waiting for the database schema migration lock")
                }
            }
        }

        var migrationFailure: Throwable? = null
        try {
            migrate()
        } catch (e: Throwable) {
            migrationFailure = e
            throw e
        } finally {
            try {
                connection.prepareStatement("SELECT RELEASE_LOCK(?)").use {
                    it.setString(1, lockName)
                    it.executeQuery().close()
                }
            } catch (e: SQLException) {
                if (migrationFailure == null) throw e
                migrationFailure.addSuppressed(e)
            }
        }
    }

    private fun recordMigration(connection: Connection, migration: Migration) {
        if (hasMigration(connection, migration.version)) return

        try {
            connection.prepareStatement(
                "INSERT INTO ${tables.migrations} (version, description, applied_at) VALUES (?, ?, ?)"
            ).use {
                it.setInt(1, migration.version)
                it.setString(2, migration.description)
                it.setLong(3, System.currentTimeMillis())
                it.executeUpdate()
            }
        } catch (e: SQLException) {
            if (!hasMigration(connection, migration.version)) {
                throw e
            }
        }
    }

    private fun hasMigration(connection: Connection, version: Int): Boolean {
        return connection.prepareStatement(
            "SELECT 1 FROM ${tables.migrations} WHERE version = ?"
        ).use {
            it.setInt(1, version)
            it.executeQuery().use { result -> result.next() }
        }
    }

    private fun getAppliedVersions(connection: Connection): Set<Int> {
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version FROM ${tables.migrations}").use { result ->
                buildSet {
                    while (result.next()) add(result.getInt("version"))
                }
            }
        }
    }

    private fun hasTable(connection: Connection, tableName: String): Boolean {
        return createTableNames(tableName).any { candidate ->
            connection.metaData.getTables(connection.catalog, null, candidate, arrayOf("TABLE")).use {
                it.next()
            }
        }
    }

    private fun hasColumn(connection: Connection, tableName: String, columnName: String): Boolean {
        return createTableNames(tableName).any { candidate ->
            connection.metaData.getColumns(connection.catalog, null, candidate, null).use {
                while (it.next()) {
                    if (it.getString("COLUMN_NAME").equals(columnName, true)) return true
                }
                false
            }
        }
    }

    private fun createTableNames(tableName: String): Set<String> {
        return setOf(tableName, tableName.lowercase(), tableName.uppercase())
    }

    private data class Migration(
        val version: Int,
        val description: String,
        val apply: (Connection) -> Unit
    )
}
