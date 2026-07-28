package com.pokeskies.skiescrates.storage.file

import com.pokeskies.skiescrates.SkiesCrates
import com.pokeskies.skiescrates.config.ConfigManager
import com.pokeskies.skiescrates.data.userdata.UsedKeyData
import com.pokeskies.skiescrates.data.userdata.UserData
import com.pokeskies.skiescrates.storage.IStorage
import com.pokeskies.skiescrates.storage.UserSaveResult
import java.util.*
import java.util.concurrent.CompletableFuture

class FileStorage : IStorage {
    private var fileData: FileData = ConfigManager.loadFile(STORAGE_FILENAME, FileData(), "", true)

    companion object {
        private const val STORAGE_FILENAME = "storage.json"
    }

    @Synchronized
    override fun getUser(uuid: UUID): UserData {
        val userData = fileData.userdata[uuid]
        return userData?.let(::UserData) ?: UserData(uuid)
    }

    @Synchronized
    override fun saveUserResult(userData: UserData): UserSaveResult {
        val storedUserData = fileData.userdata[userData.uuid]
        if ((storedUserData?.version ?: 0L) != userData.version) return UserSaveResult.CONFLICT

        val updatedUserData = UserData(userData).apply { version++ }
        fileData.userdata[userData.uuid] = updatedUserData
        if (saveFileData()) {
            userData.version = updatedUserData.version
            return UserSaveResult.SUCCESS
        }

        if (storedUserData == null) {
            fileData.userdata.remove(userData.uuid)
        } else {
            fileData.userdata[userData.uuid] = storedUserData
        }
        return UserSaveResult.FAILURE
    }

    @Synchronized
    override fun getUsedKey(uuid: UUID): UsedKeyData? {
        return fileData.usedKeys[uuid]?.let(::UsedKeyData)
    }

    @Synchronized
    override fun saveUsedKey(usedKeyData: UsedKeyData): Boolean {
        val storedUsedKey = fileData.usedKeys.put(usedKeyData.uuid, UsedKeyData(usedKeyData))
        if (saveFileData()) return true

        if (storedUsedKey == null) {
            fileData.usedKeys.remove(usedKeyData.uuid)
        } else {
            fileData.usedKeys[usedKeyData.uuid] = storedUsedKey
        }
        return false
    }

    @Synchronized
    override fun claimUsedKey(usedKeyData: UsedKeyData): Boolean {
        if (fileData.usedKeys.containsKey(usedKeyData.uuid)) return false

        fileData.usedKeys[usedKeyData.uuid] = UsedKeyData(usedKeyData)
        if (saveFileData()) return true

        fileData.usedKeys.remove(usedKeyData.uuid)
        return false
    }

    private fun saveFileData(): Boolean {
        val snapshot = FileData().apply {
            userdata = HashMap(fileData.userdata)
            usedKeys = fileData.usedKeys.mapValuesTo(HashMap()) { UsedKeyData(it.value) }
        }
        return ConfigManager.saveFile(STORAGE_FILENAME, snapshot)
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

    override fun claimUsedKeyAsync(usedKeyData: UsedKeyData): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync({
            claimUsedKey(usedKeyData)
        }, SkiesCrates.INSTANCE.asyncExecutor)
    }
}
