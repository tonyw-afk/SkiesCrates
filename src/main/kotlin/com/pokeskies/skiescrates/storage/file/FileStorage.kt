package com.pokeskies.skiescrates.storage.file

import com.pokeskies.skiescrates.SkiesCrates
import com.pokeskies.skiescrates.config.ConfigManager
import com.pokeskies.skiescrates.data.userdata.UsedKeyData
import com.pokeskies.skiescrates.data.userdata.UserData
import com.pokeskies.skiescrates.storage.IStorage
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
    override fun saveUser(userData: UserData): Boolean {
        val storedUserData = fileData.userdata[userData.uuid]
        if ((storedUserData?.version ?: 0L) != userData.version) return false

        val updatedUserData = UserData(userData).apply { version++ }
        fileData.userdata[userData.uuid] = updatedUserData
        if (saveFileData()) {
            userData.version = updatedUserData.version
            return true
        }

        if (storedUserData == null) {
            fileData.userdata.remove(userData.uuid)
        } else {
            fileData.userdata[userData.uuid] = storedUserData
        }
        return false
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
}
