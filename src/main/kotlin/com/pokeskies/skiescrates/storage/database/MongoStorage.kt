package com.pokeskies.skiescrates.storage.database

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoCredential
import com.mongodb.ServerAddress
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Updates
import com.mongodb.connection.ClusterSettings
import com.pokeskies.skiescrates.SkiesCrates
import com.pokeskies.skiescrates.config.SkiesCratesConfig
import com.pokeskies.skiescrates.data.userdata.UsedKeyData
import com.pokeskies.skiescrates.data.userdata.UserData
import com.pokeskies.skiescrates.storage.IStorage
import com.pokeskies.skiescrates.utils.Utils
import org.bson.UuidRepresentation
import org.bson.codecs.configuration.CodecRegistries
import org.bson.codecs.pojo.PojoCodecProvider
import java.io.IOException
import java.util.*
import java.util.concurrent.CompletableFuture

class MongoStorage(config: SkiesCratesConfig.Storage) : IStorage {
    private var mongoClient: MongoClient? = null
    private var mongoDatabase: MongoDatabase? = null
    private var userdataCollection: MongoCollection<UserData>? = null
    private var usedKeysCollection: MongoCollection<UsedKeyData>? = null

    init {
        try {
            var settings = MongoClientSettings.builder()
                .uuidRepresentation(UuidRepresentation.STANDARD)

            settings = if (config.urlOverride.isNotEmpty()) {
                settings.applyConnectionString(ConnectionString(config.urlOverride))
            } else {
                settings.credential(MongoCredential.createCredential(
                    config.username,
                    "admin",
                    config.password.toCharArray()
                )).applyToClusterSettings { builder: ClusterSettings.Builder ->
                    builder.hosts(listOf(ServerAddress(config.host, config.port)))
                }
            }

            this.mongoClient = MongoClients.create(settings.build())

            val codecRegistry = CodecRegistries.fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                CodecRegistries.fromCodecs(UUIDCodec()),
                CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
            )

            this.mongoDatabase = mongoClient!!.getDatabase(config.database)
                .withCodecRegistry(codecRegistry)
            this.userdataCollection = this.mongoDatabase!!.getCollection("userdata", UserData::class.java)
            this.usedKeysCollection = this.mongoDatabase!!.getCollection("used_keys", UsedKeyData::class.java)
        } catch (e: Exception) {
            throw IOException("Error while attempting to setup Mongo Database: $e")
        }
    }

    override fun getUser(uuid: UUID): UserData {
        if (mongoDatabase == null) {
            Utils.printError("There was an error while attempting to fetch data from the Mongo database!")
            return UserData(uuid)
        }
        return userdataCollection?.find(Filters.eq("_id", uuid))?.firstOrNull() ?: UserData(uuid)
    }

    override fun saveUser(userData: UserData): Boolean {
        if (mongoDatabase == null) {
            Utils.printError("There was an error while attempting to save data to the Mongo database!")
            return false
        }
        val expectedVersion = userData.version
        val versionFilter = if (expectedVersion == 0L) {
            Filters.or(Filters.eq("version", 0L), Filters.exists("version", false))
        } else {
            Filters.eq("version", expectedVersion)
        }
        val result = userdataCollection?.updateOne(
            Filters.and(Filters.eq("_id", userData.uuid), versionFilter),
            Updates.combine(
                Updates.set("crates", userData.crates),
                Updates.set("keys", userData.keys),
                Updates.set("version", expectedVersion + 1)
            )
        ) ?: return false

        if (result.wasAcknowledged() && result.matchedCount == 1L) {
            userData.version = expectedVersion + 1
            return true
        }
        if (expectedVersion != 0L) return false

        return try {
            val newUserData = UserData(userData).apply { version = 1L }
            userdataCollection?.insertOne(newUserData)
            userData.version = newUserData.version
            true
        } catch (e: MongoWriteException) {
            if (e.error.code == 11000) false else throw e
        }
    }

    override fun getUsedKey(uuid: UUID): UsedKeyData? {
        if (mongoDatabase == null) {
            Utils.printError("There was an error while attempting to fetch data from the Mongo database!")
            return null
        }
        return usedKeysCollection?.find(Filters.eq("_id", uuid))?.firstOrNull()
    }

    override fun saveUsedKey(usedKeyData: UsedKeyData): Boolean {
        if (mongoDatabase == null) {
            Utils.printError("There was an error while attempting to save data to the Mongo database!")
            return false
        }
        val query = Filters.eq("_id", usedKeyData.uuid)
        val result = this.usedKeysCollection?.replaceOne(query, usedKeyData, ReplaceOptions().upsert(true))

        return result?.wasAcknowledged() ?: false
    }

    override fun getUserAsync(uuid: UUID): CompletableFuture<UserData> {
        return CompletableFuture.supplyAsync({
            getUser(uuid)
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
        mongoClient?.close()
    }
}
