package com.pokeskies.skiescrates.storage.database.sql.providers

import com.pokeskies.skiescrates.config.SkiesCratesConfig
import com.pokeskies.skiescrates.storage.database.sql.ConnectionProvider
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException

abstract class HikariCPProvider(private val storageConfig: SkiesCratesConfig.Storage): ConnectionProvider {
    private lateinit var dataSource: HikariDataSource

    @Throws(SQLException::class)
    override fun init() {
        val config = HikariConfig()

        config.username = storageConfig.username
        config.password = storageConfig.password
        config.jdbcUrl = getConnectionURL()
        config.driverClassName = getDriverClassName()
        config.poolName = "skiescrates-${getDriverName()}"
        storageConfig.properties.forEach { (propertyName, value) -> config.addDataSourceProperty(propertyName, value) }
        config.maximumPoolSize = storageConfig.poolSettings.maximumPoolSize
        config.minimumIdle = storageConfig.poolSettings.minimumIdle
        config.keepaliveTime = storageConfig.poolSettings.keepaliveTime
        config.connectionTimeout = storageConfig.poolSettings.connectionTimeout
        config.idleTimeout = storageConfig.poolSettings.idleTimeout
        config.maxLifetime = storageConfig.poolSettings.maxLifetime
        configure(config)

        dataSource = HikariDataSource(config)
    }

    @Throws(SQLException::class)
    override fun shutdown() {
        if (this::dataSource.isInitialized)
            dataSource.close()
    }

    @Throws(SQLException::class)
    override fun createConnection(): Connection {
        if (!this::dataSource.isInitialized)
            throw SQLException("The data source is not initialized!")
        return dataSource.connection
    }

    abstract fun configure(config: HikariConfig)
    abstract fun getConnectionURL(): String
    abstract fun getDriverClassName(): String
    abstract fun getDriverName(): String

    override fun getName(): String {
        return "hikaricp - ${getDriverName()}"
    }

    override fun isInitialized(): Boolean {
        if (!this::dataSource.isInitialized)
            return false
        return !dataSource.isClosed
    }
}
