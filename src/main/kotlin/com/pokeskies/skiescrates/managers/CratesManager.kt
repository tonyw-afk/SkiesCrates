package com.pokeskies.skiescrates.managers

import com.pokeskies.skiescrates.SkiesCrates
import com.pokeskies.skiescrates.SkiesCrates.Companion.asyncScope
import com.pokeskies.skiescrates.config.ConfigManager
import com.pokeskies.skiescrates.config.Lang
import com.pokeskies.skiescrates.config.block.CrateBlockLocation
import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.CrateInstance
import com.pokeskies.skiescrates.data.CrateOpenData
import com.pokeskies.skiescrates.data.DimensionalBlockPos
import com.pokeskies.skiescrates.data.key.KeyCheckResult
import com.pokeskies.skiescrates.data.opening.OpeningInstance
import com.pokeskies.skiescrates.data.opening.inventory.InventoryOpeningAnimation
import com.pokeskies.skiescrates.data.opening.inventory.InventoryOpeningInstance
import com.pokeskies.skiescrates.data.opening.world.WorldOpeningAnimation
import com.pokeskies.skiescrates.data.opening.world.WorldOpeningInstance
import com.pokeskies.skiescrates.data.rewards.Reward
import com.pokeskies.skiescrates.economy.EconomyManager
import com.pokeskies.skiescrates.events.CrateCooldownApplyEvent
import com.pokeskies.skiescrates.events.CrateCooldownCheckEvent
import com.pokeskies.skiescrates.events.CrateCostChargeEvent
import com.pokeskies.skiescrates.events.CrateInteractionEvent
import com.pokeskies.skiescrates.events.CrateKeyCheckEvent
import com.pokeskies.skiescrates.events.CrateKeyConsumeEvent
import com.pokeskies.skiescrates.events.CrateOpenAttemptEvent
import com.pokeskies.skiescrates.events.CrateOpenEvent
import com.pokeskies.skiescrates.events.CrateOpenFailedEvent
import com.pokeskies.skiescrates.events.CrateOpenedEvent
import com.pokeskies.skiescrates.events.CratePreviewEvent
import com.pokeskies.skiescrates.events.ItemSwingEvent
import com.pokeskies.skiescrates.gui.PreviewInventory
import com.pokeskies.skiescrates.integrations.bil.BILCrateData
import com.pokeskies.skiescrates.utils.ChunkKey
import com.pokeskies.skiescrates.utils.MinecraftDispatcher
import com.pokeskies.skiescrates.utils.Utils
import com.pokeskies.skiescrates.utils.asNative
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.future.await
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.Vec3
import java.util.*

object CratesManager {
    const val CRATE_IDENTIFIER: String = "${SkiesCrates.MOD_ID}:crate"

    // Chunk -> List of Crate Instances in that chunk
    private val instancesByChunk: MutableMap<ChunkKey, MutableMap<DimensionalBlockPos, CrateInstance>> = mutableMapOf()
    private val interactionLimiter = mutableMapOf<UUID, Long>()

    init {
        registerEvents()
    }

    fun load() {
        // destroy existing instances and load from config
        instancesByChunk.values.forEach { chunkInstances ->
            chunkInstances.values.forEach { it.destroy() }
        }
        instancesByChunk.clear()

        ConfigManager.CRATES.forEach { (_, crate) ->
            loadCrate(crate)
        }
    }

    fun registerEvents() {
        ServerPlayConnectionEvents.JOIN.register(ServerPlayConnectionEvents.Join { handler, _, _ ->
            OpeningManager.getInstance(handler.player.uuid)?.stop()
        })
        CrateInteractionEvent.EVENT.register { player, crate, data ->
            Utils.printDebug("Crate interaction event triggered for player ${player.name} with crate ${crate.name} and interaction ${data.interaction}")
            if (ConfigManager.CONFIG.interactions.open.interactionTypes.contains(data.interaction)) {
                asyncScope.launch {
                    openCrate(player, crate, data, false)
                }
            } else if (ConfigManager.CONFIG.interactions.preview.interactionTypes.contains(data.interaction)) {
                previewCrate(player, crate)
            }

            return@register InteractionResult.PASS
        }

        // Preventing block breaking
        PlayerBlockBreakEvents.BEFORE.register(PlayerBlockBreakEvents.Before { level, _, blockPos, _, _ ->
            val dimensionalPos = DimensionalBlockPos(
                level.dimension().location().toString(),
                blockPos.x,
                blockPos.y,
                blockPos.z
            )
            getCrateFromPos(dimensionalPos)?.let { _ ->
                return@Before false
            }
            return@Before true
        })
        // Initially attempting to break a block
        AttackBlockCallback.EVENT.register(AttackBlockCallback { player, level, _, blockPos, _ ->
            if (player !is ServerPlayer) return@AttackBlockCallback InteractionResult.PASS

            val dimensionalPos = DimensionalBlockPos(
                level.dimension().location().toString(),
                blockPos.x,
                blockPos.y,
                blockPos.z
            )
            getCrateFromPos(dimensionalPos)?.let { instance ->
                CrateInteractionEvent.EVENT.invoker().interact(player, instance.crate, CrateOpenData(
                    dimensionalPos,
                    null,
                    if (player.isShiftKeyDown) CrateInteractionEvent.InteractionType.SHIFT_LEFT_CLICK else CrateInteractionEvent.InteractionType.LEFT_CLICK,
                ))
                return@AttackBlockCallback InteractionResult.FAIL
            }
            return@AttackBlockCallback InteractionResult.PASS
        })
        // Called when right clicking a block, whether you use an item or not
        UseBlockCallback.EVENT.register(UseBlockCallback { player, level, hand, blockHitResult ->
            if (player !is ServerPlayer) return@UseBlockCallback InteractionResult.PASS

            // Detect for a crate block
            val blockPos = DimensionalBlockPos(
                level.dimension().location().toString(),
                blockHitResult.blockPos.x,
                blockHitResult.blockPos.y,
                blockHitResult.blockPos.z
            )
            getCrateFromPos(blockPos)?.let { instance ->
                // Only execute opens on main hand to prevent duplicate execution
                if (hand == InteractionHand.MAIN_HAND) {
                    CrateInteractionEvent.EVENT.invoker().interact(player, instance.crate, CrateOpenData(
                        blockPos,
                        null,
                        if (player.isShiftKeyDown) CrateInteractionEvent.InteractionType.SHIFT_RIGHT_CLICK else CrateInteractionEvent.InteractionType.RIGHT_CLICK
                    ))
                }
                return@UseBlockCallback InteractionResult.FAIL
            }

            // Detect for a crate in hand to prevent the placement and attempt to open the crate
            val item = player.getItemInHand(hand)
            if (!item.isEmpty) {
                val crate = getCrateOrNull(item)
                if (crate != null) {
                    // Only execute opens on main hand to prevent duplicate execution
                    if (hand == InteractionHand.MAIN_HAND) {
                        CrateInteractionEvent.EVENT.invoker().interact(player, crate, CrateOpenData(
                            null,
                            item,
                            if (player.isShiftKeyDown) CrateInteractionEvent.InteractionType.SHIFT_RIGHT_CLICK else CrateInteractionEvent.InteractionType.RIGHT_CLICK
                        ))
                    }
                    return@UseBlockCallback InteractionResult.FAIL
                }

                // Prevent placing keys
                val keyId = KeyManager.getKeyOrNull(item)
                if (keyId != null) {
                    return@UseBlockCallback InteractionResult.FAIL
                }
            }

            return@UseBlockCallback InteractionResult.PASS
        })
        // Called when Right Clicking with an item/block in hand.
        // We need to detect for a crate in hand here, not key as that is handled by UseBlockCallback
        UseItemCallback.EVENT.register(UseItemCallback { player, _, hand ->
            if (player !is ServerPlayer) return@UseItemCallback InteractionResultHolder.pass(player.getItemInHand(hand))

            val item = player.getItemInHand(hand)

            getCrateOrNull(item)?.let { crate ->
                // Only execute opens on main hand to prevent duplicate execution
                if (hand == InteractionHand.MAIN_HAND) {
                    CrateInteractionEvent.EVENT.invoker().interact(player, crate, CrateOpenData(
                        null,
                        item,
                        if (player.isShiftKeyDown) CrateInteractionEvent.InteractionType.SHIFT_RIGHT_CLICK else CrateInteractionEvent.InteractionType.RIGHT_CLICK
                    ))
                }
                return@UseItemCallback InteractionResultHolder.fail(item)
            }

            KeyManager.getKeyOrNull(item)?.let { _ ->
                return@UseItemCallback InteractionResultHolder.fail(item)
            }

            return@UseItemCallback InteractionResultHolder.pass(item)
        })
        ItemSwingEvent.EVENT.register { player, itemStack, _ ->
            SkiesCrates.INSTANCE.server.execute { // Ensure we are on the main thread
                val crate = getCrateOrNull(itemStack) ?: return@execute
                CrateInteractionEvent.EVENT.invoker().interact(player, crate, CrateOpenData(
                    null,
                    itemStack,
                    if (player.isShiftKeyDown) CrateInteractionEvent.InteractionType.SHIFT_LEFT_CLICK else CrateInteractionEvent.InteractionType.LEFT_CLICK,
                ))
            }

            return@register InteractionResult.PASS
        }

        ServerChunkEvents.CHUNK_LOAD.register { level, chunk ->
            // Filters instances that have bilData but are not attached
            val unattached = getInstancesAtChunk(level, chunk).filter {
                it.bilData?.let { bilData -> !bilData.isAttached() } ?: false
            }

            for (instance in unattached) {
                instance.model?.let { modelOptions ->
                    instance.bilData = BILCrateData.create(instance, chunk, modelOptions)
                }
            }
        }
    }

    fun tick() {
        instancesByChunk.forEach { (_, chunkInstances) -> chunkInstances.values.forEach { it.tick() } }
    }

    fun loadCrate(crate: Crate) {
        if (!crate.enabled) return
        for (blockLocation in crate.block.locations) {
            loadCrateLocation(crate, blockLocation)
        }
    }

    fun loadCrateLocation(crate: Crate, blockLocation: CrateBlockLocation): CrateInstance? {
        val location = blockLocation.getDimensionalBlockPos()

        val level = Utils.getLevel(location.dimension)
        if (level == null) {
            Utils.printError("Crate ${crate.name} has an invalid dimension location: $location")
            return null
        }

        val key = ChunkKey.of(location)
        val chunkInstances = getChunkInstances(key)
        if (chunkInstances.containsKey(location)) {
            Utils.printError("Crate ${crate.name} has a duplicate location: $location")
            return null
        }

        val instance = CrateInstance(
            crate,
            level,
            location.getBlockPos(),
            location,
            blockLocation.model ?: crate.block.model,
            blockLocation.hologram ?: crate.block.hologram,
            ConfigManager.PARTICLES[blockLocation.particles ?: crate.block.particle]
        )
        chunkInstances[location] = instance
        return instance
    }

    fun unloadCrateLocation(instance: CrateInstance): Boolean {
        instance.destroy()
        val key = ChunkKey.of(instance.dimPos)
        val chunkInstances = instancesByChunk[key] ?: return false
        val removed = chunkInstances.remove(instance.dimPos) != null
        if (chunkInstances.isEmpty()) instancesByChunk.remove(key) // Remove chunk from map if the instances is empty now
        return removed
    }

    fun giveCrates(crate: Crate, player: ServerPlayer, amount: Int, silent: Boolean = false): Boolean {
        val item = crate.display.createItemStack(player)

        // TODO: Update amount to checking unique
        item.count = amount

        // Apply custom data to identify as a crate
        val tag = CompoundTag()
        tag.putString(CRATE_IDENTIFIER, crate.id)
        item.applyComponents(
            DataComponentPatch.builder()
            .set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
            .build())

        // Add to player's inventory
        player.inventory.placeItemBackInInventory(item)

        if (!silent) {
            Lang.CRATE_GIVE.forEach {
                player.sendMessage( crate.parsePlaceholders(it).asNative(player))
            }
        }

        return true
    }

    // This method is massive, but it handles a lot of things!
    suspend fun openCrate(player: ServerPlayer, crate: Crate, openData: CrateOpenData, isForced: Boolean): Boolean {
        // TODO: Check crate validity
        val attemptResult = CrateOpenAttemptEvent.EVENT.invoker().onCrateOpenAttempt(player, crate, openData, isForced)
        if (attemptResult != InteractionResult.PASS) {
            invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.ATTEMPT_CANCELLED)
            return false
        }

        interactionLimiter[player.uuid]?.let {
            if ((it + ConfigManager.CONFIG.interactionLimiter) > System.currentTimeMillis()) {
                invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.INTERACTION_LIMITED)
                return false
            }
        }

        interactionLimiter[player.uuid] = System.currentTimeMillis()

        // Check if the player is already opening a crate
        if (OpeningManager.getInstance(player.uuid) != null) {
            handleCrateFail(player, crate, openData)
            invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.ALREADY_OPENING)
            Lang.ERROR_ALREADY_OPENING.forEach {
                player.sendMessage(crate.parsePlaceholders(it).asNative(player))
            }
            return false
        }

        // Check if ANY player is opening the crate - This would mean only 1 player can open at a time
        PlayerLookup.tracking(player.serverLevel(), openData.location?.getBlockPos()).forEach { serverPlayer ->
            if(OpeningManager.getInstance(serverPlayer.uuid) != null && OpeningManager.getInstance(serverPlayer.uuid)?.crate?.block?.locations?.any{it.getDimensionalBlockPos().equals(openData.location)} == true) {
                handleCrateFail(player, crate, openData)
                Lang.ERROR_ALREADY_OPENING.forEach {
                    player.sendMessage(Component.literal("§cPlease wait! Crate is in use by someone else!"))
                }
                return false
            }
        }

        // Permission check
        if (!isForced && crate.permission.isNotEmpty() && !Permissions.check(player, crate.permission)) {
            handleCrateFail(player, crate, openData)
            invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.NO_PERMISSION)
            Lang.ERROR_NO_PERMISSION.forEach {
                player.sendMessage(crate.parsePlaceholders(it).asNative(player))
            }
            return false
        }

        // Inventory space check
        if (!isForced && crate.inventorySpace > 0 && player.inventory.items.count { it.isEmpty } < crate.inventorySpace) {
            handleCrateFail(player, crate, openData)
            invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.INVENTORY_SPACE)
            Lang.ERROR_INVENTORY_SPACE.forEach {
                player.sendMessage(crate.parsePlaceholders(it).asNative(player))
            }
            return false
        }

        // Balance check
        if (crate.cost != null && crate.cost.amount > 0) {
            val service = EconomyManager.getService(crate.cost.provider) ?: run {
                handleCrateFail(player, crate, openData)
                invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.INVALID_ECONOMY_PROVIDER)
                Utils.printError("Crate ${crate.id} has an invalid economy provider '${crate.cost.provider}'. Valid providers are: ${EconomyManager.getServices().keys.joinToString(", ")}")
                Lang.ERROR_ECONOMY_PROVIDER.forEach {
                    player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                }
                return false
            }
            if (service.balance(player, crate.cost.currency) < crate.cost.amount) {
                handleCrateFail(player, crate, openData)
                invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.INSUFFICIENT_FUNDS)
                Lang.ERROR_COST.forEach {
                    player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                }
                return false
            }
        }

        val storage = SkiesCrates.INSTANCE.storage

        val playerData = storage.getUserAsync(player.uuid).await()

        // Check for a cooldown, if one is present
        if (crate.cooldown > 0) {
            val lastOpened = playerData.getCrateCooldown(crate)
            val cooldownTime = lastOpened?.let { it + (crate.cooldown * 1000) }
            CrateCooldownCheckEvent.EVENT.invoker().onCrateCooldownCheck(
                player,
                crate,
                openData,
                lastOpened,
                cooldownTime,
                cooldownTime?.let { System.currentTimeMillis() < it } ?: false
            )
            if (lastOpened != null) {
                if (cooldownTime != null && System.currentTimeMillis() < cooldownTime) {
                    withContext(MinecraftDispatcher(player.server)) {
                        handleCrateFail(player, crate, openData)
                        invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.COOLDOWN)
                        Lang.ERROR_COOLDOWN.forEach {
                            player.sendMessage(crate.parsePlaceholders(
                                it.replace("%cooldown%", Utils.getFormattedTime((cooldownTime - System.currentTimeMillis()) / 1000))
                            ).asNative(player))
                        }
                    }
                    return false
                }
            }
        }

        // Ensure there are rewards to be given
        if (crate.rewards.isEmpty()) {
            withContext(MinecraftDispatcher(player.server)) {
                handleCrateFail(player, crate, openData)
                invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.NO_REWARDS)
                Lang.ERROR_NO_REWARDS.forEach {
                    player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                }
            }
            return false
        }

        // Check for any keys needed
        if (!isForced && crate.keys.isNotEmpty()) {
            if (!withContext(MinecraftDispatcher(player.server)) {
                // builds a list of key ids held by the player (main and off hand) for the hold_key setting
                    val heldKeyIds = if (crate.holdKey) {
                        buildSet {
                            val mainHand = player.inventory.items[player.inventory.selected]
                            if (!mainHand.isEmpty) {
                                KeyManager.getKeyOrNull(mainHand)?.id?.let { add(it) }
                            }

                            val offhand = player.offhandItem
                            if (!offhand.isEmpty) {
                                KeyManager.getKeyOrNull(offhand)?.id?.let { add(it) }
                            }
                        }
                    } else {
                        emptySet()
                    }

                    val failedResults = mutableListOf<Map.Entry<String, KeyCheckResult>>()
                    for (group in crate.keys.groups) {
                        val requiresHeldKey = crate.holdKey && group.keys.any { it in heldKeyIds }
                        val results = group.entries.associate { (keyId, amount) ->
                            val key = ConfigManager.KEYS[keyId]
                            val baseResult = key?.let {
                                KeyManager.checkPlayerForKeys(player, playerData, it, amount, false)
                            } ?: KeyCheckResult.NOT_FOUND
                            val result = when {
                                baseResult == KeyCheckResult.NOT_FOUND || baseResult == KeyCheckResult.INVALID -> baseResult
                                crate.holdKey && !requiresHeldKey -> KeyCheckResult.NOT_HOLDING
                                else -> baseResult
                            }
                            keyId to CrateKeyCheckEvent.EVENT.invoker().onCrateKeyCheck(
                                player,
                                crate,
                                openData,
                                keyId,
                                key,
                                amount,
                                crate.holdKey,
                                result
                            )
                        }
                        if (results.values.all { it == KeyCheckResult.SUCCESS }) {
                            openData.selectedKeys = group
                            return@withContext true
                        }
                        failedResults.addAll(results.entries)
                    }
                    // Sort out the highest error return from the key checks to display to the user
                    val highest = failedResults.maxBy { (_, result) -> result.priority }
                    when (highest.value) {
                        KeyCheckResult.INVALID -> {
                            handleCrateFail(player, crate, openData)
                            invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.INVALID_KEY)
                            Lang.KEY_DUPLICATE_ALERT.forEach {
                                player.sendMessage(it.asNative(player))
                            }
                            return@withContext false
                        }
                        KeyCheckResult.NOT_FOUND -> {
                            Utils.printError("Key ${highest.key} does not exist while opening crate ${crate.id} for ${player.name.string}!")
                            handleCrateFail(player, crate, openData)
                            invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.KEY_NOT_FOUND)
                            Lang.ERROR_KEY_NOT_FOUND.forEach {
                                player.sendMessage(crate.parsePlaceholders(
                                    it.replace("%key_id%", highest.key)
                                ).asNative(player))
                            }
                            return@withContext false
                        }
                        KeyCheckResult.NOT_HOLDING -> {
                            handleCrateFail(player, crate, openData)
                            invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.KEY_NOT_HOLDING)
                            Lang.ERROR_HOLD_KEYS.forEach {
                                player.sendMessage(crate.parsePlaceholders(
                                    it.replace("%key_id%", highest.key)
                                ).asNative(player))
                            }
                            return@withContext false
                        }
                        KeyCheckResult.NOT_ENOUGH -> {
                            handleCrateFail(player, crate, openData)
                            invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.NOT_ENOUGH_KEYS)
                            Lang.ERROR_MISSING_KEYS.forEach {
                                player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                            }
                            return@withContext false
                        }
                        KeyCheckResult.SUCCESS -> {}
                    }
                    false
            }) return false
        }

        // Check for crate item
        if (!isForced && openData.itemStack != null) {
            var contains = true
            withContext(MinecraftDispatcher(player.server)) {
                if (!player.inventory.contains { getCrateOrNull(it)?.id == crate.id }) {
                    contains = false
                    handleCrateFail(player, crate, openData)
                    invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.NO_CRATE_ITEM)
                    Lang.ERROR_NO_CRATE.forEach {
                        player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                    }
                }
            }
            if (!contains) return false
        }

        var cooldownAppliedAt: Long? = null

        // Take cost of opening the crate
        if (!isForced) {
            // Remove balance if needed
            if (crate.cost != null && crate.cost.amount > 0) {
                val service = EconomyManager.getService(crate.cost.provider) ?: run {
                    withContext(MinecraftDispatcher(player.server)) {
                        handleCrateFail(player, crate, openData)
                        invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.INVALID_ECONOMY_PROVIDER)
                        Utils.printError("Crate ${crate.id} has an invalid economy provider '${crate.cost.provider}'. Valid providers are: ${EconomyManager.getServices().keys.joinToString(", ")}")
                        Lang.ERROR_ECONOMY_PROVIDER.forEach {
                            player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                        }
                    }
                    return false
                }
                if (!service.withdraw(player, crate.cost.amount, crate.cost.currency)) {
                    withContext(MinecraftDispatcher(player.server)) {
                        handleCrateFail(player, crate, openData)
                        invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.BALANCE_CHANGED)
                        Lang.ERROR_BALANCE_CHANGED.forEach {
                            player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                        }
                    }
                    return false
                }
                CrateCostChargeEvent.EVENT.invoker().onCrateCostCharge(player, crate, openData, crate.cost)
            }

            // Apply a cooldown
            if (crate.cooldown > 0) {
                cooldownAppliedAt = System.currentTimeMillis()
            }
        }

        return withContext(MinecraftDispatcher(player.server)) {
            val rewardBag = crate.generateRewardBag(playerData)
            if (rewardBag.size() <= 0) {
                handleCrateFail(player, crate, openData)
                invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.NO_REWARDS)
                Lang.ERROR_NO_REWARDS.forEach {
                    player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                }
                return@withContext false
            }

            var immediateReward: Reward? = null
            var opening: OpeningInstance? = null
            val pendingRewards = if (crate.animation.isEmpty()) {
                // TODO: Update this probably. No option for selecting how many
                val reward = rewardBag.next() ?: run {
                    handleCrateFail(player, crate, openData)
                    invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.NO_REWARDS)
                    Lang.ERROR_NO_REWARDS.forEach {
                        player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                    }
                    return@withContext false
                }
                immediateReward = reward
                listOf(reward)
            } else {
                val animation = OpeningManager.getAnimation(crate.animation)?.instantiate() ?: run {
                    handleCrateFail(player, crate, openData)
                    invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.INVALID_ANIMATION)
                    Lang.ERROR_INVALID_ANIMATION.forEach {
                        player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                    }
                    return@withContext false
                }

                when (animation) {
                    is InventoryOpeningAnimation -> {
                        InventoryOpeningInstance(player, crate, animation, rewardBag, openData, playerData).also {
                            opening = it
                        }.getFinalRewards()
                    }
                    is WorldOpeningAnimation -> {
                        val positionData = openData.location ?: run {
                            handleCrateFail(player, crate, openData)
                            invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.INVALID_ANIMATION)
                            Utils.printError("No position data found for world opening animation ${crate.animation} for crate ${crate.id} for player ${player.name.string}!")
                            Lang.ERROR_INVALID_ANIMATION.forEach {
                                player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                            }
                            return@withContext false
                        }
                        val crateInstance = getCrateFromPos(positionData) ?: run {
                            handleCrateFail(player, crate, openData)
                            invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.INVALID_ANIMATION)
                            Utils.printError("No crate instance found at $positionData for world opening animation ${crate.animation} for crate ${crate.id} for player ${player.name.string}!")
                            Lang.ERROR_INVALID_ANIMATION.forEach {
                                player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                            }
                            return@withContext false
                        }
                        WorldOpeningInstance(player, crate, crateInstance, animation, rewardBag, openData).also {
                            opening = it
                        }.prepareRewards()
                    }
                    else -> {
                        handleCrateFail(player, crate, openData)
                        invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.INVALID_ANIMATION)
                        Lang.ERROR_INVALID_ANIMATION.forEach {
                            player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                        }
                        return@withContext false
                    }
                }
            }

            if (pendingRewards.isEmpty()) {
                handleCrateFail(player, crate, openData)
                invokeCrateFailedEvent(player, crate, openData, isForced, CrateOpenFailedEvent.Reason.NO_REWARDS)
                Lang.ERROR_NO_REWARDS.forEach {
                    player.sendMessage(crate.parsePlaceholders(it).asNative(player))
                }
                return@withContext false
            }

            if (!isForced) {
                if (!consumePhysicalKeys(player, crate, openData)) return@withContext false
                openData.itemStack?.shrink(1)
            }

            var keysChanged = false
            var cooldownChanged = false
            var rewardsChanged = false
            val updatedPlayerData = storage.updateUserAsync(player.uuid) { currentData ->
                if (!isForced) {
                    if (crate.cooldown > 0) {
                        val lastOpened = currentData.getCrateCooldown(crate)
                        if (lastOpened != null && lastOpened + (crate.cooldown * 1000) > System.currentTimeMillis()) {
                            cooldownChanged = true
                            return@updateUserAsync false
                        }
                        currentData.addCrateCooldown(crate, cooldownAppliedAt ?: System.currentTimeMillis())
                    }

                    for ((keyId, amount) in openData.selectedKeys) {
                        val key = ConfigManager.KEYS[keyId] ?: continue
                        if (key.virtual && !currentData.removeKeys(key, amount)) {
                            keysChanged = true
                            return@updateUserAsync false
                        }
                    }
                }

                for (reward in pendingRewards.filter { it.getPlayerLimit() > 0 }) {
                    if (!reward.canReceive(currentData, crate)) {
                        rewardsChanged = true
                        return@updateUserAsync false
                    }
                    currentData.addRewardUse(crate, reward)
                }

                currentData.addCrateUse(crate)
                true
            }.await()

            if (updatedPlayerData == null) {
                val reason = when {
                    keysChanged -> CrateOpenFailedEvent.Reason.KEYS_CHANGED
                    cooldownChanged -> CrateOpenFailedEvent.Reason.COOLDOWN
                    rewardsChanged -> CrateOpenFailedEvent.Reason.NO_REWARDS
                    else -> CrateOpenFailedEvent.Reason.STORAGE
                }
                Utils.printError("Failed to reserve crate data for ${player.name.string}! Check elsewhere for errors.")
                invokeCrateFailedEvent(player, crate, openData, isForced, reason)
                val messages = if (keysChanged) Lang.ERROR_KEYS_CHANGED else Lang.ERROR_STORAGE
                messages.forEach {
                    player.sendMessage(it.asNative())
                }
                return@withContext false
            }
            if (!isForced) {
                for ((keyId, amount) in openData.selectedKeys) {
                    val key = ConfigManager.KEYS[keyId] ?: continue
                    if (key.virtual) {
                        CrateKeyConsumeEvent.EVENT.invoker().onCrateKeyConsume(player, crate, openData, key, amount)
                    }
                }

                cooldownAppliedAt?.let {
                    CrateCooldownApplyEvent.EVENT.invoker().onCrateCooldownApply(
                        player,
                        crate,
                        openData,
                        it,
                        crate.cooldown * 1000
                    )
                }
            }

            Lang.CRATE_OPENING.forEach {
                player.sendMessage(crate.parsePlaceholders(it).asNative(player))
            }

            CrateOpenEvent.EVENT.invoker().onCrateOpen(player, crate, openData, isForced)
            if (immediateReward != null) {
                immediateReward.giveReward(player, crate)
                CrateOpenedEvent.EVENT.invoker().onCrateOpened(player, crate, openData, listOf(immediateReward))
                return@withContext true
            }

            val preparedOpening = opening ?: return@withContext false
            OpeningManager.addInstance(player.uuid, preparedOpening)
            preparedOpening.setup()
            return@withContext true
        }
    }

    private suspend fun consumePhysicalKeys(player: ServerPlayer, crate: Crate, openData: CrateOpenData): Boolean {
        for ((keyId, amount) in openData.selectedKeys) {
            val key = ConfigManager.KEYS[keyId] ?: continue
            if (key.virtual) continue

            val keySlots = player.inventory.items.withIndex().filter { (_, stack) ->
                !stack.isEmpty && KeyManager.getKeyOrNull(stack)?.id == keyId
            }.associate { (slot, stack) -> slot to stack }.toMutableMap()

            player.offhandItem.let { offhand ->
                if (!offhand.isEmpty && KeyManager.getKeyOrNull(offhand)?.id == keyId) {
                    keySlots[Inventory.SLOT_OFFHAND] = offhand
                }
            }

            val sortedKeys = keySlots.entries.sortedBy { (slot, _) ->
                when (slot) {
                    player.inventory.selected -> -2
                    Inventory.SLOT_OFFHAND -> -1
                    else -> slot
                }
            }.map { (_, stack) -> stack }

            var removed = 0
            for (stack in sortedKeys) {
                val removeAmount = minOf(stack.count, amount - removed)
                if (!KeyManager.markStackUsedAsync(stack, key, keyId, player).await()) {
                    Lang.KEY_DUPLICATE_ALERT.forEach {
                        player.sendMessage(it.asNative(player))
                    }
                    return false
                }
                stack.shrink(removeAmount)
                removed += removeAmount
                if (removed >= amount) break
            }

            if (removed != amount) {
                Utils.printError("Somehow ${player.name.string} had $amount keys on check, but only $removed could be removed!")
                invokeCrateFailedEvent(player, crate, openData, false, CrateOpenFailedEvent.Reason.KEYS_CHANGED)
                Lang.ERROR_KEYS_CHANGED.forEach {
                    player.sendMessage(crate.parsePlaceholders(it.replace("%key_id%", keyId)).asNative(player))
                }
                return false
            }

            CrateKeyConsumeEvent.EVENT.invoker().onCrateKeyConsume(player, crate, openData, key, removed)
        }
        return true
    }

    fun previewCrate(player: ServerPlayer, crate: Crate) {
        interactionLimiter[player.uuid]?.let {
            if ((it + ConfigManager.CONFIG.interactionLimiter) > System.currentTimeMillis()) {
                return
            }
        }

        interactionLimiter[player.uuid] = System.currentTimeMillis()

        val preview = ConfigManager.PREVIEW[crate.preview] ?: run {
            Lang.ERROR_INVALID_PREVIEW.forEach {
                player.sendMessage(crate.parsePlaceholders(it).asNative(player))
            }
            return
        }
        Utils.printDebug("previewCrate - Preview found, opening")

        SkiesCrates.INSTANCE.storage.getUserAsync(player.uuid).thenAccept { userData ->
            player.server.execute {
                PreviewInventory(player, crate, preview, userData).open()
                CratePreviewEvent.EVENT.invoker().onCratePreview(player, crate, preview)
            }
        }
    }

    private fun handleCrateFail(player: ServerPlayer, crate: Crate, openData: CrateOpenData) {
        crate.failure?.sound?.playSound(player)
        val force = crate.failure?.pushback ?: return
        if (openData.location != null) {
            val blockPos = openData.location.getBlockPos()
            val sourcePos = blockPos.center
            val playerPos = player.position()

            val direction = Vec3(
                playerPos.x - sourcePos.x,
                playerPos.y - sourcePos.y,
                playerPos.z - sourcePos.z
            ).normalize()

            val velocity = direction.scale(force)
            player.addDeltaMovement(velocity)
            player.hurtMarked = true
        }
    }

    private fun invokeCrateFailedEvent(
        player: ServerPlayer,
        crate: Crate,
        openData: CrateOpenData,
        isForced: Boolean,
        reason: CrateOpenFailedEvent.Reason,
    ) {
        CrateOpenFailedEvent.EVENT.invoker().onCrateOpenFailed(player, crate, openData, isForced, reason)
    }

    fun getCrateOrNull(itemStack: ItemStack): Crate? {
        val tag = itemStack.get(DataComponents.CUSTOM_DATA) ?: return null
        if (tag.contains(CRATE_IDENTIFIER)) {
            return ConfigManager.CRATES[tag.copyTag().getString(CRATE_IDENTIFIER)]
        }
        return null
    }

    private fun getChunkInstances(key: ChunkKey): MutableMap<DimensionalBlockPos, CrateInstance> =
        instancesByChunk.computeIfAbsent(key) { mutableMapOf() }

    fun getCrateFromPos(pos: DimensionalBlockPos): CrateInstance? {
        val key = ChunkKey.of(pos)
        val chunkInstances = instancesByChunk[key] ?: return null
        return chunkInstances[pos]
    }

    fun getInstancesAtChunk(level: ServerLevel, chunk: LevelChunk): List<CrateInstance> {
        val key = ChunkKey.of(level, chunk)
        val chunkInstances = instancesByChunk[key] ?: return emptyList()

        return chunkInstances.values.toList()
    }

    fun getAllInstances(): List<CrateInstance> {
        return instancesByChunk.values.flatMap { instances ->
            instances.values.toList()
        }
    }
}
