package com.pokeskies.skiescrates.storage.database.sql

import com.google.gson.reflect.TypeToken
import com.pokeskies.skiescrates.SkiesCrates
import com.pokeskies.skiescrates.config.SkiesCratesConfig
import com.pokeskies.skiescrates.data.userdata.CrateData
import com.pokeskies.skiescrates.data.userdata.UsedKeyData
import com.pokeskies.skiescrates.data.userdata.UserData
import com.pokeskies.skiescrates.storage.IStorage
import com.pokeskies.skiescrates.storage.StorageType
import com.pokeskies.skiescrates.storage.database.sql.providers.MySQLProvider
import com.pokeskies.skiescrates.storage.database.sql.providers.SQLiteProvider
import java.lang.reflect.Type
import java.sql.SQLException
import java.util.*
import java.util.concurrent.CompletableFuture

class SQLStorage(private val config: SkiesCratesConfig.Storage) : IStorage {
    val tables = TableNames(config.tablePrefix)

    private val connectionProvider: ConnectionProvider = when (config.type) {
        StorageType.MYSQL -> MySQLProvider(config)
        StorageType.SQLITE -> SQLiteProvider(config)
        else -> throw IllegalStateException("Invalid storage type!")
    }
    private val cratesType: Type = object : TypeToken<HashMap<String, CrateData>>() {}.type
    private val keysType: Type = object : TypeToken<HashMap<String, Int>>() {}.type

    init {
        connectionProvider.init()
        try {
            connectionProvider.createConnection().use {
                SchemaMigrator(tables, config.type == StorageType.MYSQL).migrate(it)
            }
        } catch (e: SQLException) {
            connectionProvider.shutdown()
            throw e
        }
    }

    override fun getUser(uuid: UUID): UserData {
        val userData = UserData(uuid)
        try {
            connectionProvider.createConnection().use {
                val statement = it.createStatement()
                val result = statement.executeQuery(String.format("SELECT * FROM ${tables.userdata} WHERE uuid='%s'", uuid.toString()))
                if (result != null && result.next()) {
                    userData.crates = SkiesCrates.INSTANCE.gson.fromJson(result.getString("crates"), cratesType)
                    userData.keys = SkiesCrates.INSTANCE.gson.fromJson(result.getString("keys"), keysType)
                    userData.version = result.getLong("version")
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return userData
    }

    override fun saveUser(userData: UserData): Boolean {
        return try {
            connectionProvider.createConnection().use {
                val nextVersion = userData.version + 1
                val updated = it.prepareStatement(
                    "UPDATE ${tables.userdata} " +
                        "SET crates = ?, `keys` = ?, version = ? WHERE uuid = ? AND version = ?"
                ).use { statement ->
                    statement.setString(1, SkiesCrates.INSTANCE.gson.toJson(userData.crates))
                    statement.setString(2, SkiesCrates.INSTANCE.gson.toJson(userData.keys))
                    statement.setLong(3, nextVersion)
                    statement.setString(4, userData.uuid.toString())
                    statement.setLong(5, userData.version)
                    statement.executeUpdate()
                }

                if (updated == 0) {
                    if (userData.version != 0L || userExists(it, userData.uuid)) return false

                    try {
                        it.prepareStatement(
                            "INSERT INTO ${tables.userdata} " +
                                "(uuid, crates, `keys`, version) VALUES (?, ?, ?, ?)"
                        ).use { statement ->
                            statement.setString(1, userData.uuid.toString())
                            statement.setString(2, SkiesCrates.INSTANCE.gson.toJson(userData.crates))
                            statement.setString(3, SkiesCrates.INSTANCE.gson.toJson(userData.keys))
                            statement.setLong(4, nextVersion)
                            statement.executeUpdate()
                        }
                    } catch (e: SQLException) {
                        if (userExists(it, userData.uuid)) return false
                        throw e
                    }
                }

                userData.version = nextVersion
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun userExists(connection: java.sql.Connection, uuid: UUID): Boolean {
        return connection.prepareStatement(
            "SELECT 1 FROM ${tables.userdata} WHERE uuid = ?"
        ).use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { it.next() }
        }
    }

    override fun getUsedKey(uuid: UUID): UsedKeyData? {
        try {
            connectionProvider.createConnection().use {
                val statement = it.createStatement()
                val result = statement.executeQuery(String.format("SELECT * FROM ${tables.usedKeys} WHERE uuid='%s'", uuid.toString()))
                if (result != null && result.next()) {
                    return UsedKeyData(
                        uuid,
                        result.getString("keyId"),
                        result.getLong("timeUsed"),
                        UUID.fromString(result.getString("player"))
                    )
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return null
    }

    override fun saveUsedKey(usedKeyData: UsedKeyData): Boolean {
        return try {
            connectionProvider.createConnection().use {
                val statement = it.createStatement()
                statement.execute(String.format("REPLACE INTO ${tables.usedKeys} (uuid, keyId, timeUsed, player) VALUES ('%s', '%s', %d, '%s')",
                    usedKeyData.uuid.toString(),
                    usedKeyData.keyId,
                    usedKeyData.timeUsed,
                    usedKeyData.player.toString()
                ))
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun getUserAsync(uuid: UUID): CompletableFuture<UserData> {
        return CompletableFuture.supplyAsync({
            try {
                getUser(uuid)
            } catch (e: Exception) {
                UserData(uuid)  // Return default data rather than throwing
            }
        }, SkiesCrates.INSTANCE.asyncExecutor)
    }

    override fun saveUserAsync(userData: UserData): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync({
            saveUser(userData)
        }, SkiesCrates.INSTANCE.asyncExecutor)
    }

    override fun getUsedKeyAsync(uuid: UUID): CompletableFuture<UsedKeyData?> {
        return CompletableFuture.supplyAsync({
            getUsedKey(uuid)
        }, SkiesCrates.INSTANCE.asyncExecutor)
    }

    override fun saveUsedKeyAsync(usedKeyData: UsedKeyData): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync({
            saveUsedKey(usedKeyData)
        }, SkiesCrates.INSTANCE.asyncExecutor)
    }

    override fun close() {
        connectionProvider.shutdown()
    }

    class TableNames(tablePrefix: String) {
        val userdata = "${tablePrefix}userdata"
        val usedKeys = "${tablePrefix}used_keys"
        val migrations = "${tablePrefix}schema_migrations"
    }
}
