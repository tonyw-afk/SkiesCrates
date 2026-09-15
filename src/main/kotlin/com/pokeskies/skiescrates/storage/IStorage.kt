package com.pokeskies.skiescrates.storage

import com.pokeskies.skiescrates.SkiesCrates
import com.pokeskies.skiescrates.config.SkiesCratesConfig
import com.pokeskies.skiescrates.data.userdata.UsedKeyData
import com.pokeskies.skiescrates.data.userdata.UserData
import com.pokeskies.skiescrates.storage.database.MongoStorage
import com.pokeskies.skiescrates.storage.database.sql.SQLStorage
import com.pokeskies.skiescrates.storage.file.FileStorage
import net.minecraft.server.level.ServerPlayer
import java.util.*
import java.util.concurrent.CompletableFuture

interface IStorage {
    companion object {
        private const val MAX_UPDATE_ATTEMPTS = 3

        fun load(config: SkiesCratesConfig.Storage): IStorage {
            return when (config.type) {
                StorageType.JSON -> FileStorage()
                StorageType.MONGO -> MongoStorage(config)
                StorageType.MYSQL, StorageType.SQLITE -> SQLStorage(config)
            }
        }
    }

    fun getUser(uuid: UUID): UserData
    fun getUser(player: ServerPlayer): UserData = getUser(player.uuid)
    fun saveUser(userData: UserData): Boolean = saveUserResult(userData) == UserSaveResult.SUCCESS
    fun saveUserResult(userData: UserData): UserSaveResult
    fun getUsedKey(uuid: UUID): UsedKeyData?
    fun saveUsedKey(usedKeyData: UsedKeyData): Boolean
    fun claimUsedKey(usedKeyData: UsedKeyData): Boolean

    fun getUserAsync(uuid: UUID): CompletableFuture<UserData>
    fun getUserAsync(player: ServerPlayer): CompletableFuture<UserData> = getUserAsync(player.uuid)
    fun saveUserAsync(userData: UserData): CompletableFuture<Boolean>
    fun updateUserAsync(uuid: UUID, update: (UserData) -> Boolean): CompletableFuture<UserData?> {
        return CompletableFuture.supplyAsync({
            repeat(MAX_UPDATE_ATTEMPTS) {
                val userData = getUser(uuid)
                if (!update(userData)) return@supplyAsync null
                when (saveUserResult(userData)) {
                    UserSaveResult.SUCCESS -> return@supplyAsync userData
                    UserSaveResult.CONFLICT -> {}
                    UserSaveResult.FAILURE -> return@supplyAsync null
                }
            }
            null
        }, SkiesCrates.INSTANCE.asyncExecutor)
    }
    fun getUsedKeyAsync(uuid: UUID): CompletableFuture<UsedKeyData?>
    fun saveUsedKeyAsync(usedKeyData: UsedKeyData): CompletableFuture<Boolean>
    fun claimUsedKeyAsync(usedKeyData: UsedKeyData): CompletableFuture<Boolean>

    fun close() {}
}
