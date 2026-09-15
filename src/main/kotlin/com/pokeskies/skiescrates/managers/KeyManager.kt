package com.pokeskies.skiescrates.managers

import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.pokeskies.skiescrates.SkiesCrates
import com.pokeskies.skiescrates.SkiesCrates.Companion.LOGGER
import com.pokeskies.skiescrates.config.ConfigManager
import com.pokeskies.skiescrates.config.Lang
import com.pokeskies.skiescrates.data.key.Key
import com.pokeskies.skiescrates.data.key.KeyCacheKey
import com.pokeskies.skiescrates.data.key.KeyCheckResult
import com.pokeskies.skiescrates.data.key.KeyDuplicateAlert
import com.pokeskies.skiescrates.data.userdata.UsedKeyData
import com.pokeskies.skiescrates.data.userdata.UserData
import com.pokeskies.skiescrates.utils.Utils
import com.pokeskies.skiescrates.utils.WebhookUtils
import com.pokeskies.skiescrates.utils.asNative
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object KeyManager {
    const val KEY_IDENTIFIER: String = "${SkiesCrates.MOD_ID}:key"
    const val KEY_UNIQUE_IDENTIFIER: String = "${KEY_IDENTIFIER}_unique_id"

    // A map to queue operations per user to avoid key data overwrites
    private val userKeyQueue = ConcurrentHashMap<UUID, CompletableFuture<*>>()

    private val playerKeyCache: AsyncLoadingCache<KeyCacheKey, Int> = Caffeine.newBuilder()
        .expireAfterWrite(30, TimeUnit.SECONDS)
        .refreshAfterWrite(10, TimeUnit.SECONDS)
        .executor(SkiesCrates.INSTANCE.asyncExecutor)
        .buildAsync { key, _ ->
            if (SkiesCrates.INSTANCE.server.playerList.getPlayer(key.playerUuid) == null) {
                return@buildAsync CompletableFuture.completedFuture(0)
            }

            try {
                CompletableFuture.supplyAsync({
                    try {
                        val userData = SkiesCrates.INSTANCE.storage.getUser(key.playerUuid)
                        userData.keys[key.keyId] ?: 0
                    } catch (e: Exception) {
                        LOGGER.error("Error fetching key cache for ${key.playerUuid}: ${e.message}")
                        0
                    }
                }, SkiesCrates.INSTANCE.asyncExecutor)
            } catch (e: Exception) {
                LOGGER.error("Failed to start async user data fetch: ${e.message}")
                CompletableFuture.completedFuture(0)
            }
        }

    private val confirmedUsedCache = Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build<UUID, UsedKeyData>()

    // Allows queueing key operations to ensure they are processed sequentially per user, waiting for the results on each operation
    fun <T> queueOperation(userId: UUID, supplier: () -> CompletableFuture<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        userKeyQueue.compute(userId) { _, prev ->
            val base = prev ?: CompletableFuture.completedFuture(Unit)
            val next = base.handle { _, _ -> }
                .thenCompose { supplier() }

            next.whenComplete { value, error ->
                if (error != null) result.completeExceptionally(error) else result.complete(value)
                // Tail cleanup to prevent queue from growing indefinitely (until restart ofc)
                userKeyQueue.compute(userId) { _, current ->
                    if (current === next) null else current
                }
            }
            next
        }
        return result
    }

    fun giveKeys(key: Key, player: ServerPlayer, amount: Int, silent: Boolean = false): CompletableFuture<Boolean> {
        if (key.virtual) {
            val storage = SkiesCrates.INSTANCE.storage

            return storage.updateUserAsync(player.uuid) { playerData ->
                playerData.addKeys(key, amount)
                true
            }.thenApplyAsync { playerData ->
                val result = playerData != null
                if (result && !silent) {
                    player.server.execute {
                        Lang.KEY_GIVE.forEach {
                            player.sendMessage(
                                it.replace("%key_name%", key.name)
                                    .replace("%amount%", amount.toString())
                                    .asNative(player)
                            )
                        }
                    }
                }
                result
            }.exceptionally { e ->
                Utils.printError("Storage was null while attempting save ${player.name.string}'s userdata while giving them keys! Check elsewhere for errors. Local Error: ${e.message}")
                Lang.ERROR_STORAGE.forEach {
                    player.sendMessage(it.asNative())
                }
                false
            }
        }

        val item = key.display.createItemStack(player)

        val tag = CompoundTag()
        tag.putString(KEY_IDENTIFIER, key.id)

        val itemsToGive = mutableListOf<ItemStack>()
        if (key.unique) {
            // If Key is unique, we need to give individual items as each needs a unique ID
            for (i in 1..amount) {
                val itemStack = item.copy()
                val tag = tag.copy()
                tag.putString(KEY_UNIQUE_IDENTIFIER, UUID.randomUUID().toString())
                itemStack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))

                itemsToGive.add(itemStack)
            }
        } else {
            item.count = amount
            item.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))

            itemsToGive.add(item)
        }

        itemsToGive.forEach {
            player.inventory.placeItemBackInInventory(it)
        }

        if (!silent) {
            Lang.KEY_GIVE.forEach {
                player.sendMessage(
                    it.replace("%key_name%", key.name)
                        .replace("%amount%", amount.toString())
                        .asNative(player)
                )
            }
        }

        return CompletableFuture.completedFuture(true)
    }

    // TODO: Allow taking non virtual keys, merge into one function with crate removals
    fun takeKeys(key: Key, player: ServerPlayer, amount: Int, silent: Boolean = false): CompletableFuture<Boolean> {
        if (key.virtual) {
            val storage = SkiesCrates.INSTANCE.storage

            return storage.updateUserAsync(player.uuid) { playerData ->
                playerData.removeKeys(key, amount)
            }
                .thenApplyAsync { playerData ->
                    val result = playerData != null
                    if (result && !silent) {
                        player.server.execute {
                            Lang.KEY_TAKE.forEach {
                                player.sendMessage(
                                    it.replace("%key_name%", key.name)
                                        .replace("%amount%", amount.toString())
                                        .asNative(player)
                                )
                            }
                        }
                    }
                    result
                }.exceptionally { _ ->
                    Utils.printError("Storage was null while attempting save ${player.name.string}'s userdata while taking keys from them! Check elsewhere for errors.")
                    Lang.ERROR_STORAGE.forEach {
                        player.sendMessage(it.asNative())
                    }
                    false
                }
        }

        return CompletableFuture.completedFuture(false)
    }

    // TODO: Allow taking non virtual keys, merge into one function with crate removals
    fun setKeys(key: Key, player: ServerPlayer, amount: Int, silent: Boolean = false): CompletableFuture<Boolean> {
        if (key.virtual) {
            val storage = SkiesCrates.INSTANCE.storage

            return storage.updateUserAsync(player.uuid) { playerData ->
                playerData.setKeys(key, amount)
                true
            }.thenApplyAsync { playerData ->
                val result = playerData != null
                if (result && !silent) {
                    player.server.execute {
                        Lang.KEY_SET.forEach {
                            player.sendMessage(
                                it.replace("%key_name%", key.name)
                                    .replace("%amount%", amount.toString())
                                    .asNative(player)
                            )
                        }
                    }
                }
                result
            }.exceptionally { _ ->
                Utils.printError("Storage was null while attempting save ${player.name.string}'s userdata while setting their keys! Check elsewhere for errors.")
                Lang.ERROR_STORAGE.forEach {
                    player.sendMessage(it.asNative())
                }
                false
            }
        }

        return CompletableFuture.completedFuture(false)
    }

    fun getKeyOrNull(itemStack: ItemStack): Key? {
        val tag = itemStack.get(DataComponents.CUSTOM_DATA) ?: return null

        if (tag.contains(KEY_IDENTIFIER)) {
            return ConfigManager.KEYS[tag.copyTag().getString(KEY_IDENTIFIER)]
        }

        // Migration from other crate mods
        return ConfigManager.CONFIG.migration.keys?.firstNotNullOfOrNull { instance ->
            val key = ConfigManager.KEYS[instance.key] ?: run {
                Utils.printError("Migration Key ${instance.key} did not exist while attempting to find valid keys from player!")
                return@firstNotNullOfOrNull null
            }

            if (tag.contains(instance.nbt.key)) {
                val value = tag.copyTag().getString(instance.nbt.key)
                if (value != null && value == instance.nbt.value) {
                    return key
                }
            }
            null
        }
    }

    fun cleanCache() {
        playerKeyCache.synchronous().asMap().keys.forEach { key ->
            if (SkiesCrates.INSTANCE.server.playerList.getPlayer(key.playerUuid) == null) {
                playerKeyCache.synchronous().invalidate(key)
                Utils.printDebug("cleanCache - Removed offline player ${key.playerUuid} from key cache")
            }
        }
    }

    fun getCachedKeys(uuid: UUID, keyId: String): Int {
        return playerKeyCache.get(KeyCacheKey(uuid, keyId)).getNow(0) ?: 0
    }

    fun isUniqueUUIDCached(uuid: UUID): Boolean {
        return confirmedUsedCache.getIfPresent(uuid) != null
    }

    fun markUniqueUUIDUsedAsync(data: UsedKeyData): CompletableFuture<Boolean> {
        return SkiesCrates.INSTANCE.storage.claimUsedKeyAsync(data).thenApply { claimed ->
            if (claimed) confirmedUsedCache.put(data.uuid, data)
            claimed
        }
    }

    fun isUniqueUUIDUsedAsync(uuid: UUID): CompletableFuture<Boolean> {
        if (isUniqueUUIDCached(uuid)) return CompletableFuture.completedFuture(true)

        return try {
            SkiesCrates.INSTANCE.storage.getUsedKeyAsync(uuid).thenApply { data ->
                val used = data != null
                if (used) confirmedUsedCache.put(uuid, data)
                used
            }
        } catch (_: Exception) {
            CompletableFuture.completedFuture(false)
        }
    }

    fun isUniqueUUIDUsed(uuid: UUID): Boolean {
        if (isUniqueUUIDCached(uuid)) return true
        val data = SkiesCrates.INSTANCE.storage.getUsedKey(uuid)
        val used = data != null
        if (used) confirmedUsedCache.put(uuid, data)
        return used
    }

    fun checkPlayerForKeys(player: ServerPlayer, playerData: UserData, key: Key, amount: Int, requiresHolding: Boolean): KeyCheckResult {
       return if (key.virtual) {
           KeyCheckResult.getStandardResult(playerData.keys[key.id]?.let {
               it >= amount
           } ?: false)
        } else {
            var count = 0
            val keys = player.inventory.items.withIndex().filter { (_, stack) ->
                if (getKeyOrNull(stack)?.id != key.id) return@filter false
                if (!validateStack(player, key, stack)) return KeyCheckResult.INVALID
                true
            }.associate { (slot, stack) -> slot to stack }.toMutableMap()

           player.offhandItem.let { offhand ->
               if (!offhand.isEmpty && getKeyOrNull(offhand)?.id == key.id && validateStack(player, key, offhand)) {
                   keys[Inventory.SLOT_OFFHAND] = offhand
               }
           }

           if (requiresHolding && !keys.containsKey(player.inventory.selected) && !keys.containsKey(Inventory.SLOT_OFFHAND)) {
               return KeyCheckResult.NOT_HOLDING
           }

           keys.forEach { (_, stack) ->
               count += stack.count
           }

           KeyCheckResult.getStandardResult(count >= amount)
        }
    }

    // Runs a validation check on key items if the key type is marked as unique.
    // Returns whether the key was valid or not. If false, it was a duplicate!
    private fun validateStack(player: ServerPlayer, key: Key, itemStack: ItemStack): Boolean {
        if (key.unique) {
            val tag = itemStack.get(DataComponents.CUSTOM_DATA) ?: run {
                alertDuplicateKey(player, key, KeyDuplicateAlert.MISSING_UUID)
                itemStack.count = 0
                return false
            }
            val uniqueId = tag.copyTag().getString(KEY_UNIQUE_IDENTIFIER)
            if (uniqueId.isEmpty()) {
                alertDuplicateKey(player, key, KeyDuplicateAlert.MISSING_UUID)
                itemStack.count = 0
                return false
            }

            if (itemStack.count > 1) {
                alertDuplicateKey(player, key, KeyDuplicateAlert.STACKED, mapOf("%amount%" to itemStack.count.toString(), "%key_uuid%" to uniqueId))
                itemStack.count = 0
                return false
            }

            val uuid = try {
                UUID.fromString(uniqueId)
            } catch (_: IllegalArgumentException) {
                alertDuplicateKey(player, key, KeyDuplicateAlert.INVALID_UUID, mapOf("%key_uuid%" to uniqueId))
                itemStack.count = 0
                return false
            }

            if (isUniqueUUIDCached(uuid)) {
                alertDuplicateKey(player, key, KeyDuplicateAlert.ALREADY_USED, mapOf("%key_uuid%" to uniqueId))
                itemStack.count = 0
                return false
            }
        }

        return true
    }

    fun markStackUsedAsync(
        itemStack: ItemStack,
        key: Key,
        keyId: String,
        player: ServerPlayer
    ): CompletableFuture<Boolean> {
        if (!key.unique) return CompletableFuture.completedFuture(true)

        val data = itemStack.get(DataComponents.CUSTOM_DATA)
            ?: return CompletableFuture.completedFuture(false)
        val uuidString = data.copyTag().getString(KEY_UNIQUE_IDENTIFIER)
        val uuid = try {
            UUID.fromString(uuidString)
        } catch (_: Exception) {
            return CompletableFuture.completedFuture(false)
        }

        return markUniqueUUIDUsedAsync(UsedKeyData(
            uuid,
            keyId,
            System.currentTimeMillis(),
            player.uuid
        ))
    }

    private fun alertDuplicateKey(player: ServerPlayer, key: Key, alert: KeyDuplicateAlert, placeholders: Map<String, String> = emptyMap()) {
        var message = alert.message
            .replace("%key_id%", key.id)
            .replace("%player%", player.name.string)
            .replace("%player_uuid%", player.uuid.toString())

        placeholders.forEach { (key, value) ->
            message = message.replace(key, value)
        }

        Utils.printError("Duplicate Key Alert: $message")

        WebhookUtils.sendKeyAlert(player, message)
    }
}
