package com.pokeskies.skiescrates.storage.database.sql.providers

import com.pokeskies.skiescrates.SkiesCrates
import com.pokeskies.skiescrates.config.SkiesCratesConfig
import com.zaxxer.hikari.HikariConfig
import java.io.File

class SQLiteProvider(config: SkiesCratesConfig.Storage) : HikariCPProvider(config) {
    override fun getConnectionURL(): String = String.format(
        "jdbc:sqlite:%s",
        File(SkiesCrates.INSTANCE.configDir, "storage.db").toPath().toAbsolutePath()
    )

    override fun getDriverClassName(): String = "org.sqlite.JDBC"
    override fun getDriverName(): String = "sqlite"
    override fun configure(config: HikariConfig) {
        config.maximumPoolSize = 1
        config.minimumIdle = 1
    }
}
